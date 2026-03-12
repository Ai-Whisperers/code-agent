package com.eneve.agent.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eneve.agent.bitbucket.AgentComment;
import com.eneve.agent.bitbucket.BitbucketCloudService;
import com.eneve.agent.diff.DiffFormatter;
import com.eneve.agent.model.RepoCoordinates;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ReviewCommentProcessor {

    private static final Logger LOG = Logger.getLogger(ReviewCommentProcessor.class);

    private static final Pattern REVIEWED_UP_TO_PATTERN =
            Pattern.compile("<!-- agent-reviewed-up-to:([0-9a-f]{7,40}) -->");

    @Inject BitbucketCloudService bitbucketService;
    @Inject CommentStore commentStore;

    /**
     * Parse the structured JSON review from Claude and post inline comments + overall summary.
     * Deduplicates against existing agent comments and embeds a reviewed-up-to SHA marker.
     * Falls back to posting the entire output as a general comment if JSON parsing fails.
     */
    public String postReviewComments(String reviewOutput, RepoCoordinates coords, String prId,
                                     List<AgentComment> existingComments, String headSha,
                                     String reviewJobId,
                                     Map<String, TreeSet<Integer>> commentableLines) {
        String ws = coords.workspace();
        String slug = coords.repoSlug();

        String json = extractJsonBlock(reviewOutput);
        if (json == null) {
            LOG.warn("Review output is not structured JSON, posting as single general comment");
            safeComment(() -> bitbucketService.addPrComment(ws, slug, prId, reviewOutput));
            return reviewOutput;
        }

        Set<String> alreadyCommented = new HashSet<>();
        for (AgentComment existing : existingComments) {
            if (!existing.filePath().isEmpty() && existing.line() > 0) {
                alreadyCommented.add(existing.filePath() + ":" + existing.line());
            }
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

            com.fasterxml.jackson.databind.JsonNode findings = root.path("findings");
            int inlineCount = 0;
            int skippedCount = 0;
            int snappedCount = 0;
            if (findings.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode finding : findings) {
                    String file = finding.path("file").asText("");
                    int line = finding.path("line").asInt(0);
                    String severity = finding.path("severity").asText("INFO");
                    String category = finding.path("category").asText("");
                    String description = finding.path("description").asText("");
                    String suggestion = finding.path("suggestion").asText("");

                    StringBuilder comment = new StringBuilder();
                    comment.append("**[").append(severity).append("]** ");
                    if (!category.isEmpty()) {
                        comment.append("_").append(category).append("_ — ");
                    }
                    comment.append(description);
                    if (!suggestion.isEmpty()) {
                        comment.append("\n\n**Suggestion:** ").append(suggestion);
                    }

                    if (!file.isEmpty() && line > 0) {
                        String normalizedFile = normalizeDiffPath(file);
                        TreeSet<Integer> validLines = commentableLines.get(normalizedFile);
                        if (validLines == null) {
                            validLines = commentableLines.get(file);
                        }
                        if (validLines != null && !validLines.isEmpty()) {
                            int snapped = DiffFormatter.snapToNearest(validLines, line);
                            if (snapped != line) {
                                LOG.infof("Snapping line %d -> %d for %s (nearest commentable line)",
                                        line, snapped, file);
                                line = snapped;
                                snappedCount++;
                            }
                            file = normalizedFile != null
                                    && commentableLines.containsKey(normalizedFile)
                                    ? normalizedFile : file;
                        }

                        String dedupKey = file + ":" + line;
                        if (alreadyCommented.contains(dedupKey)) {
                            LOG.infof("Skipping duplicate comment at %s (already posted)", dedupKey);
                            skippedCount++;
                            continue;
                        }
                        try {
                            long commentId = bitbucketService.addInlinePrComment(ws, slug, prId,
                                    file, line, comment.toString());
                            inlineCount++;
                            if (commentId > 0) {
                                commentStore.save(commentId, new CommentContext(
                                        prId, ws, slug, file, line,
                                        category, severity, description, reviewJobId));
                            }
                        } catch (Exception e) {
                            LOG.warnf("Failed to post inline comment at %s:%d, falling back to general: %s",
                                    file, line, e.getMessage());
                            final String fallbackFile = file;
                            final int fallbackLine = line;
                            safeComment(() -> bitbucketService.addPrComment(ws, slug, prId,
                                    "**" + fallbackFile + ":" + fallbackLine + "** — " + comment));
                        }
                    } else {
                        safeComment(() -> bitbucketService.addPrComment(ws, slug, prId, comment.toString()));
                    }
                }
            }

            String verdict = root.path("verdict").asText("");
            String summary = root.path("summary").asText("");
            StringBuilder overallComment = new StringBuilder();
            overallComment.append("## Code Review Summary\n\n");
            if (!verdict.isEmpty()) {
                overallComment.append("**Verdict: ").append(verdict).append("**\n\n");
            }
            if (!summary.isEmpty()) {
                overallComment.append(summary);
            }
            overallComment.append("\n\n---\n_").append(inlineCount)
                    .append(" inline comment(s) posted on specific lines.");
            if (skippedCount > 0) {
                overallComment.append(" ").append(skippedCount)
                        .append(" duplicate(s) skipped from previous review.");
            }
            overallComment.append("_");

            if (headSha != null && !headSha.isBlank()) {
                overallComment.append("\n<!-- agent-reviewed-up-to:").append(headSha).append(" -->");
            }

            safeComment(() -> bitbucketService.addPrComment(ws, slug, prId, overallComment.toString()));

            LOG.infof("Posted %d inline comments + summary to PR #%s (%d duplicates skipped, %d lines snapped)",
                    inlineCount, prId, skippedCount, snappedCount);
            return overallComment.toString();

        } catch (Exception e) {
            LOG.warnf("Failed to parse review JSON, posting as general comment: %s", e.getMessage());
            safeComment(() -> bitbucketService.addPrComment(ws, slug, prId, reviewOutput));
            return reviewOutput;
        }
    }

    /**
     * Scan existing agent comments for the last-reviewed commit SHA marker.
     */
    public static String extractLastReviewedSha(List<AgentComment> existingComments) {
        for (AgentComment comment : existingComments) {
            if (comment.filePath().isEmpty() && comment.line() == 0) {
                Matcher m = REVIEWED_UP_TO_PATTERN.matcher(comment.content());
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    static String extractJsonBlock(String text) {
        if (text == null || text.isBlank()) return null;

        int jsonStart = text.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = text.indexOf('\n', jsonStart);
            int jsonEnd = text.indexOf("```", contentStart + 1);
            if (contentStart >= 0 && jsonEnd > contentStart) {
                return text.substring(contentStart + 1, jsonEnd).trim();
            }
        }

        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        return null;
    }

    static String normalizeDiffPath(String path) {
        if (path == null) return null;
        if (path.startsWith("b/")) return path.substring(2);
        if (path.startsWith("a/")) return path.substring(2);
        if (path.startsWith("/")) return path.substring(1);
        return path;
    }

    private void safeComment(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warnf("Failed to post Bitbucket comment (non-fatal): %s", e.getMessage());
        }
    }
}
