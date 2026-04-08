package com.eneve.agent.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Usage;
import com.eneve.agent.agent.model.AiCallRecord;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.store.AiCallStore;
import com.eneve.agent.diff.DiffFormatter;
import com.eneve.agent.diff.DiffLine;
import com.eneve.agent.diff.DiffHunk;
import com.eneve.agent.diff.ParsedDiffFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.eneve.agent.settings.SettingsService;
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
    static final String DIAGRAM_PLACEHOLDER_PREFIX = "DIAGRAM_UPLOAD:";

    /**
     * A diagram that should be rendered to PNG and uploaded to get a real image URL.
     *
     * @param filename     suggested filename for the uploaded PNG (e.g. {@code mermaid-pr42-1.png})
     * @param placeholder  the placeholder URL embedded in the markdown body
     *                     (e.g. {@code DIAGRAM_UPLOAD:mermaid-pr42-1.png})
     * @param mermaidSource raw Mermaid diagram source (without fences)
     */
    public record PendingDiagram(String filename, String placeholder, String mermaidSource) {}

    /**
     * The result of {@link #generate}: the formatted markdown body plus any diagrams
     * that need to be rendered and uploaded before the comment is posted.
     * <p>
     * When {@code pendingDiagrams} is empty the {@code body} is ready to post as-is.
     * When diagrams are present, each placeholder in {@code body} must be replaced
     * with the real upload URL before posting.
     */
    public record SummaryResult(String body, List<PendingDiagram> pendingDiagrams) {}

    ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    AnthropicClient client;

    @Inject
    SettingsService settings;

    @Inject
    AiCallStore aiCallStore;

    @Inject
    TokenBudgetTracker tokenBudgetTracker;

    @Inject
    MermaidRenderer mermaidRenderer;

    @Inject
    PromptTemplateService promptTemplates;

    /** Overrides the settings lookup when set directly (e.g. in tests). */
    Boolean diagramUploadEnabled;

    /**
     * Generate a PR summary from the diff.
     * <p>
     * Returns a {@link SummaryResult} containing the formatted markdown body and any
     * {@link PendingDiagram}s that must be rendered and uploaded before posting.
     * Returns {@code null} if the diff is empty or the Claude call fails.
     *
     * @param prId          pull request identifier (used to generate stable diagram filenames)
     * @param diagramContext optional code-graph relationship text for diagram generation;
     *                       pass null or blank to skip diagram generation
     */
    public SummaryResult generate(String prTitle, String targetBranch, List<ParsedDiffFile> parsedFiles,
                                  String jobId, String diagramContext, String prId) {
        if (parsedFiles == null || parsedFiles.isEmpty()) {
            return null;
        }

        String compactDiff = buildCompactDiff(parsedFiles);
        String fileList = buildFileList(parsedFiles);
        String prompt = buildPrompt(prTitle, targetBranch, fileList, compactDiff, diagramContext);

        String responseText = callClaude(prompt, jobId);
        if (responseText == null) {
            return null;
        }

        return formatComment(responseText, parsedFiles, prId);
    }

    /** Returns the HTML marker used to identify existing summary comments. */
    public static String marker() {
        return MARKER;
    }

    private String buildPrompt(String prTitle, String targetBranch, String fileList,
                               String compactDiff, String diagramContext) {
        boolean includeDiagrams = diagramContext != null && !diagramContext.isBlank();

        String diagramSection = includeDiagrams
                ? "\n## Code Relationships\n" + diagramContext + "\n"
                : "";

        String diagramOutputSpec = includeDiagrams
                ? ",\n  \"diagrams\": [\n    {\n      \"title\": \"Short descriptive title\",\n      \"mermaid\": \"sequenceDiagram or classDiagram syntax here (no fences)\"\n    }\n  ]\n"
                : "";

        String diagramRule = includeDiagrams
                ? "- Include a \"diagrams\" array only when the PR affects component interactions (API calls, event flows, service chains) or type hierarchies. Omit it entirely for trivial single-file changes.\n- Each diagram must use valid Mermaid syntax (sequenceDiagram or classDiagram). Use the Code Relationships section above to ground the diagram in real call chains. Do not invent relationships that are not in the diff or the code graph.\n- Keep diagrams concise: max 10 participants or nodes.\n"
                : "";

        return promptTemplates.resolve("pr-summary", Map.of(
                "PR_TITLE", prTitle != null ? prTitle : "(untitled)",
                "TARGET_BRANCH", targetBranch != null ? targetBranch : "(unknown)",
                "FILE_LIST", fileList,
                "DIAGRAM_SECTION", diagramSection,
                "DIFF", compactDiff,
                "DIAGRAM_OUTPUT_SPEC", diagramOutputSpec,
                "DIAGRAM_RULE", diagramRule
        ));
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

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 5_000;

    private String callClaude(String prompt, String jobId) {
        String modelName = settings.get("anthropic.summary-model",
                settings.get("anthropic.fast-model", "claude-haiku-4-5"));
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
            response = callWithRetry(params);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            aiCallStore.save(new AiCallRecord(
                    null, jobId, "PR_SUMMARY", modelName, null,
                    0, 0, 0, 0,
                    null, null, durationMs,
                    true, e.getMessage(), Instant.now(),
                    prompt, null,
                    null, 0));
            LOG.errorf("PR summary Claude call failed: %s", e.getMessage());
            return null;
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

        String responseText = null;
        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                responseText = block.asText().text().trim();
                break;
            }
        }

        Usage usage = response.usage();
        String stopReason = response.stopReason().map(sr -> sr.toString()).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, jobId, "PR_SUMMARY", modelName, null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now(),
                prompt, responseText,
                null, 0));
        LOG.infof("PR summary generated — tokens: in=%d, out=%d, duration=%dms",
                usage.inputTokens(), usage.outputTokens(), durationMs);

        return responseText;
    }

    private Message callWithRetry(MessageCreateParams params) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                tokenBudgetTracker.waitIfNeeded();
                return client.messages().create(params);
            } catch (RateLimitException e) {
                if (attempt == MAX_RETRIES) throw e;
                long waitMs = INITIAL_BACKOFF_MS * (1L << attempt);
                waitMs += (long) (waitMs * 0.5 * ThreadLocalRandom.current().nextDouble());
                LOG.warnf("PR summary rate limited (attempt %d/%d), waiting %dms",
                        attempt + 1, MAX_RETRIES, waitMs);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during rate limit backoff", ie);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for token budget", e);
            }
        }
        throw new RuntimeException("Exhausted retries after rate limiting");
    }

    /**
     * Parse Claude's JSON response and format into a {@link SummaryResult}.
     * <p>
     * When the platform is Bitbucket and {@code diagramUploadEnabled} is {@code true},
     * diagram blocks are replaced with placeholder URLs and the raw Mermaid source is
     * returned as {@link PendingDiagram} records for the caller to render and upload.
     * For all other platforms, diagrams are rendered inline via {@link MermaidRenderer}
     * (native fences or mermaid.ink) and the pending list is empty.
     * <p>
     * Falls back to a minimal walkthrough table if JSON parsing fails.
     *
     * @param prId pull request identifier, used to build stable diagram filenames
     */
    SummaryResult formatComment(String responseText, List<ParsedDiffFile> parsedFiles, String prId) {
        String cleaned = responseText.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).strip();
            }
        }

        boolean uploadEnabled = (diagramUploadEnabled != null) ? diagramUploadEnabled
                : Boolean.parseBoolean(settings.get("pr.summary.diagram.upload.enabled", "true"));
        boolean usePlaceholders = uploadEnabled
                && "bitbucket".equalsIgnoreCase(mermaidRenderer.platform().trim());

        StringBuilder comment = new StringBuilder();
        List<PendingDiagram> pendingDiagrams = new ArrayList<>();
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

            JsonNode diagrams = root.path("diagrams");
            if (diagrams.isArray() && !diagrams.isEmpty()) {
                comment.append("\n### Sequence Diagrams\n\n");
                int diagramIndex = 0;
                for (JsonNode diagram : diagrams) {
                    String title = diagram.path("title").asText("").strip();
                    String mermaid = diagram.path("mermaid").asText("").strip();
                    if (title.isEmpty() || mermaid.isEmpty()) {
                        continue;
                    }
                    diagramIndex++;
                    comment.append("<details><summary>").append(title).append("</summary>\n\n");
                    if (usePlaceholders) {
                        String safeId = (prId != null ? prId : "pr").replaceAll("[^a-zA-Z0-9_-]", "_");
                        String filename = "mermaid-" + safeId + "-" + diagramIndex + ".png";
                        String placeholder = DIAGRAM_PLACEHOLDER_PREFIX + filename;
                        pendingDiagrams.add(new PendingDiagram(filename, placeholder, mermaid));
                        comment.append("![").append(title).append("](").append(placeholder).append(")");
                    } else {
                        comment.append(mermaidRenderer.render(title, mermaid));
                    }
                    comment.append("\n\n</details>\n\n");
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
        return new SummaryResult(comment.toString(), Collections.unmodifiableList(pendingDiagrams));
    }
}
