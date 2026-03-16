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
import java.util.concurrent.ThreadLocalRandom;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Usage;
import com.eneve.agent.diff.DiffFormatter;
import com.eneve.agent.diff.ParsedDiffFile;
import com.eneve.agent.workspace.WorkspaceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Checks whether previously flagged review findings have been addressed in the
 * latest incremental diff.
 * <p>
 * All qualifying findings are batched into a single Claude call that returns a
 * JSON array, replacing the original per-finding sequential API calls and
 * reducing latency by N-1 round-trips. If the batch call fails, falls back to
 * the original per-finding approach.
 */
@ApplicationScoped
public class FindingResolver {

    private static final Logger LOG = Logger.getLogger(FindingResolver.class);
    private static final int CONTEXT_RADIUS = 15;
    private static final int HUNK_PROXIMITY = 5;
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 5_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ConfigProperty(name = "anthropic.fast-model", defaultValue = "claude-3-5-haiku-20241022")
    String fastModelName;

    @Inject
    AnthropicClient client;

    @Inject
    AiCallStore aiCallStore;

    @Inject
    TokenBudgetTracker tokenBudgetTracker;

    private record FindingCandidate(OpenFinding finding, String contextSnippet) {}

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

        List<FindingCandidate> candidates = new ArrayList<>();
        for (OpenFinding finding : openFindings) {
            String filePath = finding.filePath();
            if (!changedFiles.contains(filePath)) {
                continue;
            }
            TreeSet<Integer> lines = changedLines.get(filePath);
            if (lines == null || !hasProximity(lines, finding.line(), HUNK_PROXIMITY)) {
                continue;
            }
            String contextSnippet = readContextSnippet(workspace, filePath, finding.line());
            if (contextSnippet != null) {
                candidates.add(new FindingCandidate(finding, contextSnippet));
            }
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        LOG.infof("FindingResolver: checking %d candidate finding(s) via batched call", candidates.size());

        try {
            return askClaudeIfFixedBatch(candidates, jobId);
        } catch (Exception e) {
            LOG.warnf("Batch resolution call failed, falling back to individual calls: %s", e.getMessage());
            return resolveIndividually(candidates, jobId);
        }
    }

    // ── Batched resolution ────────────────────────────────────────────────────

    private List<Long> askClaudeIfFixedBatch(List<FindingCandidate> candidates, String jobId) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Review whether the following code findings have been addressed in the latest commits.\n");
        prompt.append("For each finding, examine the current code context and determine if the issue is resolved.\n\n");

        for (int i = 0; i < candidates.size(); i++) {
            FindingCandidate c = candidates.get(i);
            prompt.append("---\n");
            prompt.append("Finding ").append(i + 1).append(":\n");
            prompt.append("File: ").append(c.finding().filePath())
                    .append(", Line: ").append(c.finding().line()).append("\n");
            prompt.append("Issue: ").append(c.finding().findingText()).append("\n");
            prompt.append("Current code:\n").append(c.contextSnippet()).append("\n");
        }

        prompt.append("---\n");
        prompt.append("Respond with ONLY a JSON array. For each finding (1-indexed), state whether it is resolved.\n");
        prompt.append("Example: [{\"index\":1,\"resolved\":true},{\"index\":2,\"resolved\":false}]\n");
        prompt.append("Output only the JSON array, no explanation.");

        int maxTokens = Math.max(candidates.size() * 15 + 50, 256);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(fastModelName))
                .maxTokens(maxTokens)
                .messages(List.of(
                        MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content(prompt.toString())
                                .build()
                ))
                .build();

        long startNs = System.nanoTime();
        Message response = callWithRetry(params, jobId, "FINDING_RESOLUTION");
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

        Usage usage = response.usage();
        String stopReason = response.stopReason().map(Object::toString).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, jobId, "FINDING_RESOLUTION", fastModelName, null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now()));
        tokenBudgetTracker.recordUsage(usage.inputTokens(), usage.outputTokens());

        LOG.infof("Batch resolution: %d findings checked in %dms (tokens: in=%d out=%d)",
                candidates.size(), durationMs, usage.inputTokens(), usage.outputTokens());

        return parseBatchResponse(response, candidates);
    }

    private List<Long> parseBatchResponse(Message response, List<FindingCandidate> candidates) {
        String text = "";
        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                text = block.asText().text().trim();
                break;
            }
        }

        List<Long> resolved = new ArrayList<>();
        try {
            String cleaned = text;
            if (cleaned.startsWith("```")) {
                int nl = cleaned.indexOf('\n');
                int last = cleaned.lastIndexOf("```");
                if (nl > 0 && last > nl) {
                    cleaned = cleaned.substring(nl + 1, last).strip();
                }
            }
            JsonNode root = OBJECT_MAPPER.readTree(cleaned);
            if (!root.isArray()) {
                LOG.warnf("Batch resolution response is not a JSON array, falling back");
                throw new IllegalStateException("Response is not a JSON array");
            }
            for (JsonNode entry : root) {
                int index = entry.path("index").asInt(-1);
                boolean isResolved = entry.path("resolved").asBoolean(false);
                if (isResolved && index >= 1 && index <= candidates.size()) {
                    FindingCandidate c = candidates.get(index - 1);
                    resolved.add(c.finding().commentId());
                    LOG.infof("Finding on %s:%d resolved (batch)",
                            c.finding().filePath(), c.finding().line());
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse batch resolution JSON ('%s'): %s — falling back to individual calls",
                    text.length() > 80 ? text.substring(0, 80) + "..." : text, e.getMessage());
            throw new RuntimeException("Batch parse failed: " + e.getMessage(), e);
        }
        return resolved;
    }

    // ── Per-finding fallback ──────────────────────────────────────────────────

    private List<Long> resolveIndividually(List<FindingCandidate> candidates, String jobId) {
        List<Long> resolved = new ArrayList<>();
        for (FindingCandidate c : candidates) {
            try {
                boolean addressed = askClaudeIfFixedSingle(c.finding(), c.contextSnippet(), jobId);
                if (addressed) {
                    resolved.add(c.finding().commentId());
                    LOG.infof("Finding on %s:%d resolved (individual fallback)",
                            c.finding().filePath(), c.finding().line());
                }
            } catch (Exception e) {
                LOG.warnf("Resolution check failed for %s:%d (non-fatal): %s",
                        c.finding().filePath(), c.finding().line(), e.getMessage());
            }
        }
        return resolved;
    }

    private boolean askClaudeIfFixedSingle(OpenFinding finding, String contextSnippet, String jobId) {
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
                .model(Model.of(fastModelName))
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
            response = callWithRetry(params, jobId, "FINDING_RESOLUTION");
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            aiCallStore.save(new AiCallRecord(
                    null, jobId, "FINDING_RESOLUTION", fastModelName, null,
                    0, 0, 0, 0,
                    null, null, durationMs,
                    true, e.getMessage(), Instant.now()));
            throw e;
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

        Usage usage = response.usage();
        String stopReason = response.stopReason().map(Object::toString).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, jobId, "FINDING_RESOLUTION", fastModelName, null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now()));
        tokenBudgetTracker.recordUsage(usage.inputTokens(), usage.outputTokens());

        String responseText = "";
        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                responseText = block.asText().text().trim();
                break;
            }
        }
        return responseText.toUpperCase(Locale.ROOT).contains("YES");
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private Message callWithRetry(MessageCreateParams params, String jobId, String jobType) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                tokenBudgetTracker.waitIfNeeded();
                return client.messages().create(params);
            } catch (RateLimitException e) {
                if (attempt == MAX_RETRIES) throw e;
                long waitMs = INITIAL_BACKOFF_MS * (1L << attempt);
                waitMs += (long) (waitMs * 0.5 * ThreadLocalRandom.current().nextDouble());
                LOG.warnf("Rate limited in FindingResolver (attempt %d/%d), waiting %dms",
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
}
