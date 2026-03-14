package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Usage;
import com.eneve.agent.diff.DiffFormatter;
import com.eneve.agent.diff.ParsedDiffFile;
import com.eneve.agent.workspace.WorkspaceContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Checks whether previously flagged review findings have been addressed
 * in the latest incremental diff. Uses a lightweight Claude call per
 * finding to make the determination.
 */
@ApplicationScoped
public class FindingResolver {

    private static final Logger LOG = Logger.getLogger(FindingResolver.class);
    private static final int CONTEXT_RADIUS = 15;
    private static final int HUNK_PROXIMITY = 5;

    @ConfigProperty(name = "anthropic.api.key")
    String apiKey;

    @ConfigProperty(name = "anthropic.model", defaultValue = "claude-sonnet-4-20250514")
    String modelName;

    @Inject
    AiCallStore aiCallStore;

    /**
     * Determine which open findings have been addressed by the incremental diff.
     *
     * @return list of comment IDs whose findings are now resolved
     */
    public List<Long> resolveAddressedFindings(List<OpenFinding> openFindings,
                                               List<ParsedDiffFile> incrementalDiff,
                                               WorkspaceContext workspace,
                                               String jobId) {
        if (openFindings.isEmpty() || incrementalDiff.isEmpty()) {
            return List.of();
        }

        Map<String, TreeSet<Integer>> changedLines = DiffFormatter.buildCommentableLines(incrementalDiff);
        Set<String> changedFiles = changedLines.keySet();

        List<Long> resolved = new ArrayList<>();

        for (OpenFinding finding : openFindings) {
            String filePath = finding.filePath();
            if (!changedFiles.contains(filePath)) {
                continue;
            }

            TreeSet<Integer> lines = changedLines.get(filePath);
            boolean lineOverlap = lines != null && hasProximity(lines, finding.line(), HUNK_PROXIMITY);

            if (!lineOverlap) {
                continue;
            }

            String contextSnippet = readContextSnippet(workspace, filePath, finding.line());
            if (contextSnippet == null) {
                continue;
            }

            try {
                boolean addressed = askClaudeIfFixed(finding, contextSnippet, jobId);
                if (addressed) {
                    resolved.add(finding.commentId());
                    LOG.infof("Finding on %s:%d resolved by new commits", filePath, finding.line());
                }
            } catch (Exception e) {
                LOG.warnf("Resolution check failed for %s:%d (non-fatal): %s",
                        filePath, finding.line(), e.getMessage());
            }
        }

        return resolved;
    }

    private boolean hasProximity(TreeSet<Integer> changedLines, int targetLine, int radius) {
        Integer floor = changedLines.floor(targetLine + radius);
        Integer ceiling = changedLines.ceiling(targetLine - radius);
        if (floor != null && floor >= targetLine - radius) return true;
        if (ceiling != null && ceiling <= targetLine + radius) return true;
        return false;
    }

    private String readContextSnippet(WorkspaceContext workspace, String filePath, int line) {
        try {
            Path resolved = workspace.resolve(filePath);
            if (!Files.exists(resolved) || Files.isDirectory(resolved)) {
                return null;
            }
            List<String> allLines = Files.readAllLines(resolved);
            int start = Math.max(0, line - CONTEXT_RADIUS - 1);
            int end = Math.min(allLines.size(), line + CONTEXT_RADIUS);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(String.format("%d| %s\n", i + 1, allLines.get(i)));
            }
            return sb.toString();
        } catch (SecurityException | IOException e) {
            LOG.warnf("Failed to read context for %s:%d: %s", filePath, line, e.getMessage());
            return null;
        }
    }

    private boolean askClaudeIfFixed(OpenFinding finding, String contextSnippet, String jobId) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        String prompt = """
                The following review finding was posted on a previous commit:
                File: %s, Line: %d
                Finding: %s

                The developer has since pushed new commits. Here is the current state of the code around line %d:
                %s

                Has this finding been addressed? Reply with only YES or NO.""".formatted(
                finding.filePath(), finding.line(), finding.findingText(),
                finding.line(), contextSnippet);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(10)
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
                    null, jobId, "FINDING_RESOLUTION", modelName, null,
                    0, 0, 0, 0,
                    null, null, durationMs,
                    true, e.getMessage(), Instant.now()));
            throw e;
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

        Usage usage = response.usage();
        String stopReason = response.stopReason().map(sr -> sr.toString()).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, jobId, "FINDING_RESOLUTION", modelName, null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now()));

        String responseText = "";
        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                responseText = block.asText().text().trim();
                break;
            }
        }

        LOG.debugf("Resolution check for %s:%d — response: '%s'",
                finding.filePath(), finding.line(), responseText);

        return responseText.toUpperCase(Locale.ROOT).contains("YES");
    }
}
