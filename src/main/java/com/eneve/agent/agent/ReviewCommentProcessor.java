package com.eneve.agent.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eneve.agent.agent.model.CommentContext;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.GitPlatformService;
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
    private static final Pattern VERDICT_PATTERN =
            Pattern.compile("\\*\\*Verdict: ([A-Z_]+)\\*\\*");
    private static final Pattern INLINE_COUNT_PATTERN =
            Pattern.compile("(\\d+) inline comment");
    private static final Pattern DETAILS_CONTENT_PATTERN =
            Pattern.compile("<details><summary>Previous reviews</summary>\\n\\n(.*?)\\n\\n</details>",
                    Pattern.DOTALL);

    @Inject GitPlatformService platformService;
    @Inject CommentStore commentStore;

    /**
     * Parse the structured JSON review from Claude and post inline comments + overall summary.
     * On re-reviews the summary comment is updated in-place rather than posted as a new comment.
     * Previous review metadata is archived into a collapsible history block.
     * Falls back to posting the entire output as a general comment if JSON parsing fails.
     */
    public String postReviewComments(String reviewOutput, RepoCoordinates coords, String prId,
                                     List<AgentComment> existingComments, String headSha,
                                     String reviewJobId,
                                     Map<String, TreeSet<Integer>> commentableLines,
                                     int resolvedCount) {
        String org = coords.organization();
        String project = coords.project();
        String repo = coords.repository();

        String json = extractJsonBlock(reviewOutput);
        if (json == null) {
            LOG.warn("Review output is not structured JSON, posting as single general comment");
            safeComment(() -> platformService.addPrComment(org, project, repo, prId, reviewOutput));
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
                            long commentId = platformService.addInlinePrComment(org, project, repo, prId,
                                    file, line, comment.toString());
                            inlineCount++;
                            if (commentId > 0) {
                                commentStore.save(commentId, new CommentContext(
                                        prId, org, project, repo, file, line,
                                        category, severity, description, reviewJobId));
                            }
                        } catch (Exception e) {
                            LOG.warnf("Failed to post inline comment at %s:%d, falling back to general: %s",
                                    file, line, e.getMessage());
                            final String fallbackFile = file;
                            final int fallbackLine = line;
                            safeComment(() -> platformService.addPrComment(org, project, repo, prId,
                                    "**" + fallbackFile + ":" + fallbackLine + "** — " + comment));
                        }
                    } else {
                        safeComment(() -> platformService.addPrComment(org, project, repo, prId, comment.toString()));
                    }
                }
            }

            String verdict = root.path("verdict").asText("");
            String summary = root.path("summary").asText("");

            // Archive the previous summary into a history block, then build the new body.
            String previousSummaryBody = findPreviousSummaryBody(existingComments);
            String historySection = buildUpdatedHistorySection(previousSummaryBody);

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
            if (resolvedCount > 0) {
                overallComment.append(" ").append(resolvedCount)
                        .append(" issue(s) auto-resolved from previous review.");
            }
            if (skippedCount > 0) {
                overallComment.append(" ").append(skippedCount)
                        .append(" duplicate(s) skipped from previous review.");
            }
            overallComment.append("_");

            if (!historySection.isEmpty()) {
                overallComment.append(historySection);
            }

            if (headSha != null && !headSha.isBlank()) {
                overallComment.append("\n<!-- agent-reviewed-up-to:").append(headSha).append(" -->");
            }

            String summaryBody = overallComment.toString();
            postOrUpdateSummary(org, project, repo, prId, summaryBody, reviewJobId);

            LOG.infof("Posted %d inline comments + summary to PR #%s (%d duplicates skipped, %d lines snapped)",
                    inlineCount, prId, skippedCount, snappedCount);
            return summaryBody;

        } catch (Exception e) {
            LOG.warnf("Failed to parse review JSON, posting as general comment: %s", e.getMessage());
            safeComment(() -> platformService.addPrComment(org, project, repo, prId, reviewOutput));
            return reviewOutput;
        }
    }

    /**
     * Update the existing summary comment in-place if we have its ID; otherwise create a new one.
     * Falls back to creating a new comment if the update fails (e.g. the comment was deleted).
     */
    private void postOrUpdateSummary(String org, String project, String repo, String prId,
                                     String body, String reviewJobId) {
        var existingSummaryId = commentStore.findSummaryCommentId(prId, org, repo);
        if (existingSummaryId.isPresent()) {
            long commentId = existingSummaryId.get();
            try {
                platformService.updatePrComment(org, project, repo, prId, commentId, body);
                // Refresh the stored ID (the comment ID stays the same, but the job ID changes).
                commentStore.saveSummaryComment(commentId, prId, org, project, repo, reviewJobId);
                LOG.infof("Updated existing summary comment %d on PR #%s", commentId, prId);
                return;
            } catch (Exception e) {
                LOG.warnf("Failed to update summary comment %d (comment may have been deleted): %s",
                        commentId, e.getMessage());
            }
        }

        // No existing summary or update failed — create a fresh one.
        safeComment(() -> {
            long newId = platformService.addPrComment(org, project, repo, prId, body);
            if (newId > 0) {
                commentStore.saveSummaryComment(newId, prId, org, project, repo, reviewJobId);
                LOG.infof("Created new summary comment %d on PR #%s", newId, prId);
            }
        });
    }

    /**
     * Scan existing agent comments for the last-reviewed commit SHA marker.
     * Returns the most recent match (last in list) so incremental diffs are
     * computed from the latest reviewed commit, not an older one.
     */
    public static String extractLastReviewedSha(List<AgentComment> existingComments) {
        String lastFound = null;
        for (AgentComment comment : existingComments) {
            if (comment.filePath().isEmpty() && comment.line() == 0) {
                Matcher m = REVIEWED_UP_TO_PATTERN.matcher(comment.content());
                if (m.find()) {
                    lastFound = m.group(1);
                }
            }
        }
        return lastFound;
    }

    /**
     * Find the body of the most recent review summary comment (a general comment containing the
     * reviewed-up-to SHA marker). Returns null if no previous summary exists.
     */
    static String findPreviousSummaryBody(List<AgentComment> existingComments) {
        String lastFound = null;
        for (AgentComment comment : existingComments) {
            if (comment.filePath().isEmpty() && comment.line() == 0
                    && REVIEWED_UP_TO_PATTERN.matcher(comment.content()).find()) {
                lastFound = comment.content();
            }
        }
        return lastFound;
    }

    /**
     * Build the collapsible history section to append to the updated summary.
     * Archives the previous review's SHA, verdict and inline count as a bullet entry,
     * preserving any older entries that already exist in the previous summary.
     */
    static String buildUpdatedHistorySection(String previousSummaryBody) {
        if (previousSummaryBody == null || previousSummaryBody.isBlank()) {
            return "";
        }

        Matcher shaMatcher = REVIEWED_UP_TO_PATTERN.matcher(previousSummaryBody);
        String prevSha = shaMatcher.find()
                ? shaMatcher.group(1).substring(0, Math.min(8, shaMatcher.group(1).length()))
                : "unknown";

        Matcher verdictMatcher = VERDICT_PATTERN.matcher(previousSummaryBody);
        String prevVerdict = verdictMatcher.find() ? verdictMatcher.group(1) : "REVIEWED";

        Matcher countMatcher = INLINE_COUNT_PATTERN.matcher(previousSummaryBody);
        String prevCount = countMatcher.find() ? countMatcher.group(1) : "?";

        String newEntry = "- **" + prevSha + "**: " + prevVerdict + " — " + prevCount + " finding(s)";

        Matcher detailsMatcher = DETAILS_CONTENT_PATTERN.matcher(previousSummaryBody);
        String existingEntries = detailsMatcher.find() ? detailsMatcher.group(1).strip() : "";

        String allEntries = existingEntries.isBlank()
                ? newEntry
                : newEntry + "\n" + existingEntries;

        return "\n\n<details><summary>Previous reviews</summary>\n\n" + allEntries + "\n\n</details>";
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
            LOG.warnf("Failed to post PR comment (non-fatal): %s", e.getMessage());
        }
    }
}
