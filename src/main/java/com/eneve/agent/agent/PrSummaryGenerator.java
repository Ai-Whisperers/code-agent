package com.eneve.agent.agent;

import java.time.Instant;
import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Usage;
import com.eneve.agent.diff.DiffFormatter;
import com.eneve.agent.diff.DiffLine;
import com.eneve.agent.diff.DiffHunk;
import com.eneve.agent.diff.ParsedDiffFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Generates a CodeRabbit-style PR description with a high-level summary and
 * per-file walkthrough table. Uses a single Claude call (no tool-use loop)
 * to keep latency and cost low — this runs before the full review.
 */
@ApplicationScoped
public class PrSummaryGenerator {

    private static final Logger LOG = Logger.getLogger(PrSummaryGenerator.class);
    private static final String MARKER = "<!-- agent-pr-summary -->";
    private static final int MAX_DIFF_CHARS = 60_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ConfigProperty(name = "anthropic.api.key")
    String apiKey;

    @ConfigProperty(name = "anthropic.model", defaultValue = "claude-sonnet-4-20250514")
    String modelName;

    @Inject
    AiCallStore aiCallStore;

    /**
     * Generate a PR summary comment body from the diff. Returns fully formatted
     * markdown ready to be posted as a PR comment, or null if generation fails.
     */
    public String generate(String prTitle, String targetBranch, List<ParsedDiffFile> parsedFiles,
                           String jobId) {
        if (parsedFiles == null || parsedFiles.isEmpty()) {
            return null;
        }

        String compactDiff = buildCompactDiff(parsedFiles);
        String fileList = buildFileList(parsedFiles);
        String prompt = buildPrompt(prTitle, targetBranch, fileList, compactDiff);

        String responseText = callClaude(prompt, jobId);
        if (responseText == null) {
            return null;
        }

        return formatComment(responseText, parsedFiles);
    }

    /** Returns the HTML marker used to identify existing summary comments. */
    public static String marker() {
        return MARKER;
    }

    private String buildPrompt(String prTitle, String targetBranch, String fileList,
                               String compactDiff) {
        return """
                Analyze this pull request diff and produce a JSON summary.

                ## PR Information
                - **Title**: %s
                - **Target branch**: %s

                ## Changed Files
                %s

                ## Diff
                ```
                %s
                ```

                ## Output Format
                Return ONLY a JSON object (no markdown fences, no extra text):
                {
                  "summary": "2-3 sentence high-level description of what this PR accomplishes and why. \
                Write from the perspective of describing the PR to a reviewer.",
                  "walkthrough": [
                    {
                      "file": "exact/path/to/file.java",
                      "changes": "Concise one-line description of what changed in this file"
                    }
                  ]
                }

                Rules:
                - The summary should describe the overall PURPOSE and IMPACT, not list individual files.
                - Each walkthrough entry must correspond to a file in the diff.
                - Keep walkthrough descriptions concise (one line, under 120 chars).
                - Use technical but accessible language.
                - For renamed/moved files, mention the rename.
                - For deleted files, say "Removed" with a brief reason if discernible.
                - Output ONLY the JSON object. No explanation, no markdown fences.
                """.formatted(
                prTitle != null ? prTitle : "(untitled)",
                targetBranch != null ? targetBranch : "(unknown)",
                fileList,
                compactDiff
        );
    }

    /**
     * Build a compact diff representation that fits within token budget.
     * Uses DiffFormatter's annotated output, truncated at file boundaries.
     */
    private String buildCompactDiff(List<ParsedDiffFile> files) {
        List<ParsedDiffFile> truncated = DiffFormatter.truncateAtFileBoundary(files, MAX_DIFF_CHARS);
        String annotated = DiffFormatter.toAnnotated(truncated);
        if (truncated.size() < files.size()) {
            annotated += "\n... (" + (files.size() - truncated.size()) + " more file(s) not shown)\n";
        }
        return annotated;
    }

    private String buildFileList(List<ParsedDiffFile> files) {
        StringBuilder sb = new StringBuilder();
        for (ParsedDiffFile file : files) {
            int added = 0, removed = 0;
            for (DiffHunk hunk : file.hunks()) {
                for (DiffLine line : hunk.lines()) {
                    if (line.type() == DiffLine.Type.ADDED) added++;
                    else if (line.type() == DiffLine.Type.REMOVED) removed++;
                }
            }
            sb.append("- `%s` (+%d/-%d)\n".formatted(file.path(), added, removed));
        }
        return sb.toString();
    }

    private String callClaude(String prompt, String jobId) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(4096)
                .messages(List.of(
                        MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content(prompt)
                                .build()
                ))
                .build();

        long startNs = System.nanoTime();
        Message response;
        try {
            response = client.messages().create(params);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            aiCallStore.save(new AiCallRecord(
                    null, jobId, "PR_SUMMARY", modelName, null,
                    0, 0, 0, 0,
                    null, null, durationMs,
                    true, e.getMessage(), Instant.now()));
            LOG.errorf("PR summary Claude call failed: %s", e.getMessage());
            return null;
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

        Usage usage = response.usage();
        String stopReason = response.stopReason().map(sr -> sr.toString()).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, jobId, "PR_SUMMARY", modelName, null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now()));

        LOG.infof("PR summary generated — tokens: in=%d, out=%d, duration=%dms",
                usage.inputTokens(), usage.outputTokens(), durationMs);

        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                return block.asText().text().trim();
            }
        }
        return null;
    }

    /**
     * Parse Claude's JSON response and format into a markdown comment body.
     * Falls back to a minimal walkthrough if JSON parsing fails.
     */
    String formatComment(String responseText, List<ParsedDiffFile> parsedFiles) {
        String cleaned = responseText.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).strip();
            }
        }

        StringBuilder comment = new StringBuilder();
        comment.append(MARKER).append("\n");
        comment.append("## PR Summary\n\n");

        try {
            JsonNode root = objectMapper.readTree(cleaned);

            String summary = root.path("summary").asText("");
            if (!summary.isEmpty()) {
                comment.append("### Walkthrough\n");
                comment.append(summary).append("\n\n");
            }

            JsonNode walkthrough = root.path("walkthrough");
            if (walkthrough.isArray() && !walkthrough.isEmpty()) {
                comment.append("### Changes\n\n");
                comment.append("| File | Summary |\n");
                comment.append("|------|---------|\n");
                for (JsonNode entry : walkthrough) {
                    String file = entry.path("file").asText("");
                    String changes = entry.path("changes").asText("");
                    if (!file.isEmpty()) {
                        changes = changes.replace("|", "\\|");
                        comment.append("| `").append(file).append("` | ").append(changes).append(" |\n");
                    }
                }
            }

        } catch (Exception e) {
            LOG.warnf("Failed to parse PR summary JSON, building minimal walkthrough: %s", e.getMessage());
            comment.append("### Changes\n\n");
            comment.append("| File | Lines Changed |\n");
            comment.append("|------|---------------|\n");
            for (ParsedDiffFile file : parsedFiles) {
                int added = 0, removed = 0;
                for (DiffHunk hunk : file.hunks()) {
                    for (DiffLine line : hunk.lines()) {
                        if (line.type() == DiffLine.Type.ADDED) added++;
                        else if (line.type() == DiffLine.Type.REMOVED) removed++;
                    }
                }
                comment.append("| `").append(file.path()).append("` | +")
                        .append(added).append("/-").append(removed).append(" |\n");
            }
        }

        comment.append("\n---\n_Generated by Code Agent_");
        return comment.toString();
    }
}
