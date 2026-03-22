package com.eneve.agent.agent;

import java.time.Instant;
import com.eneve.agent.agent.store.AiCallStore;
import com.eneve.agent.agent.model.AiCallRecord;
import com.eneve.agent.agent.model.ChatEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonMissing;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.RateLimitException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.Usage;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.tools.ToolRegistry;
import com.eneve.agent.workspace.WorkspaceContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Implements the core agentic loop: sends messages to Claude with tool definitions,
 * dispatches tool calls to the ToolRegistry, and iterates until Claude produces
 * a final text response or the iteration cap is reached.
 */
@ApplicationScoped
public class ClaudeToolUseLoop {

    private static final Logger LOG = Logger.getLogger(ClaudeToolUseLoop.class);

    /** Pool used to execute independent read-only tool calls in parallel within one iteration. */
    private static final ExecutorService PARALLEL_TOOL_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "parallel-tool");
        t.setDaemon(true);
        return t;
    });

    @ConfigProperty(name = "anthropic.model", defaultValue = "claude-sonnet-4-20250514")
    String modelName;

    @ConfigProperty(name = "anthropic.max-tokens", defaultValue = "8192")
    long maxTokens;

    @ConfigProperty(name = "run-fix.max-loop-iterations", defaultValue = "50")
    int maxIterations;

    @Inject
    AnthropicClient client;

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    AiCallStore aiCallStore;

    @Inject
    TokenBudgetTracker tokenBudgetTracker;

    /**
     * Run the agentic tool-use loop with a custom tool set and initial user message.
     */
    public String run(String systemPrompt, WorkspaceContext workspace,
                      List<ToolUnion> tools, String initialUserMessage,
                      String jobId, String jobType) {
        return doRun(systemPrompt, workspace, tools, initialUserMessage, jobId, jobType, maxIterations);
    }

    /**
     * Run the agentic tool-use loop with a custom tool set, initial user message,
     * and a per-call iteration cap override.
     */
    public String run(String systemPrompt, WorkspaceContext workspace,
                      List<ToolUnion> tools, String initialUserMessage,
                      int maxIterationsOverride, String jobId, String jobType) {
        return doRun(systemPrompt, workspace, tools, initialUserMessage, jobId, jobType, maxIterationsOverride);
    }

    /**
     * Run the agentic tool-use loop with default tools.
     */
    public String run(String systemPrompt, WorkspaceContext workspace,
                      String jobId, String jobType) {
        return doRun(systemPrompt, workspace, ToolDefinitions.all(),
                "Please complete the task described in the system prompt. "
                        + "Start by listing the repository structure, then proceed.",
                jobId, jobType, maxIterations);
    }

    /**
     * Run the agentic tool-use loop with default tools and a per-call iteration cap override.
     * Use this for job types that require more iterations than the global default.
     */
    public String run(String systemPrompt, WorkspaceContext workspace,
                      int maxIterationsOverride, String jobId, String jobType) {
        return doRun(systemPrompt, workspace, ToolDefinitions.all(),
                "Please complete the task described in the system prompt. "
                        + "Start by listing the repository structure, then proceed.",
                jobId, jobType, maxIterationsOverride);
    }

    private String doRun(String systemPrompt, WorkspaceContext workspace,
                         List<ToolUnion> tools, String initialUserMessage,
                         String jobId, String jobType, int iterationCap) {
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(initialUserMessage)
                .build());

        for (int iteration = 0; iteration < iterationCap; iteration++) {
            LOG.infof("Agent loop iteration %d/%d", iteration + 1, iterationCap);

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.of(modelName))
                    .maxTokens(maxTokens)
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .system(systemPrompt)
                    .messages(messages)
                    .tools(tools)
                    .build();

            long startNs = System.nanoTime();
            Message response;
            try {
                response = callWithRetry(params);
            } catch (Exception e) {
                long durationMs = (System.nanoTime() - startNs) / 1_000_000;
                aiCallStore.save(new AiCallRecord(
                        null, jobId, jobType, modelName, iteration + 1,
                        0, 0, 0, 0,
                        null, null, durationMs,
                        true, e.getMessage(), Instant.now(),
                        null, null));
                throw e;
            }
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            logUsage(response, iteration + 1);
            tokenBudgetTracker.recordUsage(response.usage().inputTokens(), response.usage().outputTokens());

            List<ContentBlockParam> toolResults = new ArrayList<>();
            StringBuilder textAccumulator = new StringBuilder();
            List<ContentBlockParam> assistantBlocks = new ArrayList<>();
            List<String> toolNamesList = new ArrayList<>();

            // Collect tool-use blocks first so we can decide whether to run them in parallel
            List<ToolUseBlock> toolUseBlocks = response.content().stream()
                    .filter(ContentBlock::isToolUse)
                    .map(ContentBlock::asToolUse)
                    .toList();

            boolean hasToolUse = !toolUseBlocks.isEmpty();

            // Run in parallel only when every tool in this batch is read-only
            Map<String, String> toolResultMap;
            if (toolUseBlocks.size() > 1
                    && toolUseBlocks.stream().allMatch(t -> {
                        ToolExecutor ex = toolRegistry.get(t.name());
                        return ex != null && ex.isReadOnly();
                    })) {
                LOG.debugf("Executing %d read-only tools in parallel", toolUseBlocks.size());
                List<CompletableFuture<Map.Entry<String, String>>> futures = toolUseBlocks.stream()
                        .map(t -> CompletableFuture.supplyAsync(
                                () -> Map.entry(t.id(), dispatchTool(t, workspace)),
                                PARALLEL_TOOL_EXECUTOR))
                        .toList();
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                toolResultMap = futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            } else {
                toolResultMap = new HashMap<>();
                for (ToolUseBlock t : toolUseBlocks) {
                    toolResultMap.put(t.id(), dispatchTool(t, workspace));
                }
            }

            // Reconstruct assistant blocks and tool results in original response order
            for (ContentBlock block : response.content()) {
                if (block.isText()) {
                    textAccumulator.append(block.asText().text());
                    assistantBlocks.add(ContentBlockParam.ofText(block.asText().toParam()));
                } else if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    toolNamesList.add(toolUse.name());
                    String result = toolResultMap.get(toolUse.id());

                    LOG.infof("Tool call: %s (id=%s)", toolUse.name(), toolUse.id());
                    LOG.debugf("Tool result for %s: %s", toolUse.name(),
                            result.length() > 200 ? result.substring(0, 200) + "..." : result);

                    assistantBlocks.add(ContentBlockParam.ofToolUse(toolUse.toParam()));

                    boolean isError = result.startsWith("ERROR:");
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(result)
                                    .isError(isError)
                                    .build()
                    ));
                }
            }

            Usage usage = response.usage();
            String stopReason = response.stopReason().map(sr -> sr.toString()).orElse(null);
            String toolNamesCsv = toolNamesList.isEmpty() ? null
                    : String.join(",", toolNamesList);

            aiCallStore.save(new AiCallRecord(
                    null, jobId, jobType, modelName, iteration + 1,
                    usage.inputTokens(), usage.outputTokens(),
                    usage.cacheCreationInputTokens().orElse(0L),
                    usage.cacheReadInputTokens().orElse(0L),
                    stopReason, toolNamesCsv, durationMs,
                    false, null, Instant.now(),
                    null, null));

            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(assistantBlocks)
                    .build());

            if (!hasToolUse) {
                LOG.infof("Agent finished after %d iterations", iteration + 1);
                return textAccumulator.toString();
            }

            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(toolResults)
                    .build());

            if (response.stopReason().isPresent()
                    && response.stopReason().get() == StopReason.END_TURN
                    && !hasToolUse) {
                LOG.infof("Agent ended turn after %d iterations", iteration + 1);
                return textAccumulator.toString();
            }
        }

        LOG.warnf("Agent loop hit max iterations (%d)", iterationCap);
        return "Agent loop reached maximum iterations without completing. Partial work may exist.";
    }

    // ──────────────────────────────────────────────────────────────────────
    // Streaming loop
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Run the agentic loop in streaming mode.
     *
     * <p>Text deltas are emitted via {@code eventSink} as they arrive from the API.
     * Tool lifecycle events ({@link ChatEvent.ToolStart}/{@link ChatEvent.ToolEnd}) are emitted
     * synchronously while tool execution is in progress.
     * On completion a {@link ChatEvent.Done} is emitted; on unrecoverable error a
     * {@link ChatEvent.Error} is emitted instead.
     *
     * @param systemPrompt      system prompt for the loop
     * @param workspace         workspace context (may be null for chat mode)
     * @param tools             tool definitions available to Claude
     * @param initialUserMessage the first user message
     * @param jobId             identifier for AI call logging
     * @param jobType           job type string for AI call logging
     * @param iterationCap      maximum tool-use iterations before giving up
     * @param eventSink         callback that receives each {@link ChatEvent}
     */
    /**
     * Run the streaming loop with prior conversation history and content blocks.
     *
     * <p>The new user message content blocks are appended to {@code priorHistory} and the resulting
     * full conversation is passed to Claude. Returns the updated message list so the
     * caller can persist it for subsequent turns.
     *
     * @param priorHistory messages from earlier turns in this conversation (may be empty)
     * @param userContentBlocks content blocks for the new user message (text, images, etc.)
     * @return the full message list including the newly completed turn
     */
    public List<MessageParam> runStreaming(String systemPrompt, WorkspaceContext workspace,
                                           List<ToolUnion> tools, List<ContentBlockParam> userContentBlocks,
                                           List<MessageParam> priorHistory,
                                           String jobId, String jobType, int iterationCap,
                                           Consumer<ChatEvent> eventSink) {
        List<MessageParam> messages = new ArrayList<>(priorHistory);
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(userContentBlocks)
                .build());
        doRunStreaming(systemPrompt, workspace, tools, messages, jobId, jobType, iterationCap, eventSink);
        return messages;
    }

    /**
     * Run the streaming loop with prior conversation history.
     *
     * <p>The new user message is appended to {@code priorHistory} and the resulting
     * full conversation is passed to Claude. Returns the updated message list so the
     * caller can persist it for subsequent turns.
     *
     * @param priorHistory messages from earlier turns in this conversation (may be empty)
     * @return the full message list including the newly completed turn
     */
    public List<MessageParam> runStreaming(String systemPrompt, WorkspaceContext workspace,
                                           List<ToolUnion> tools, String initialUserMessage,
                                           List<MessageParam> priorHistory,
                                           String jobId, String jobType, int iterationCap,
                                           Consumer<ChatEvent> eventSink) {
        List<MessageParam> messages = new ArrayList<>(priorHistory);
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(initialUserMessage)
                .build());
        doRunStreaming(systemPrompt, workspace, tools, messages, jobId, jobType, iterationCap, eventSink);
        return messages;
    }

    public void runStreaming(String systemPrompt, WorkspaceContext workspace,
                             List<ToolUnion> tools, String initialUserMessage,
                             String jobId, String jobType, int iterationCap,
                             Consumer<ChatEvent> eventSink) {
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(initialUserMessage)
                .build());
        doRunStreaming(systemPrompt, workspace, tools, messages, jobId, jobType, iterationCap, eventSink);
    }

    private void doRunStreaming(String systemPrompt, WorkspaceContext workspace,
                                List<ToolUnion> tools, List<MessageParam> messages,
                                String jobId, String jobType, int iterationCap,
                                Consumer<ChatEvent> eventSink) {
        try {
            for (int iteration = 0; iteration < iterationCap; iteration++) {
                LOG.infof("Streaming agent loop iteration %d/%d", iteration + 1, iterationCap);

                MessageCreateParams params = MessageCreateParams.builder()
                        .model(Model.of(modelName))
                        .maxTokens(maxTokens)
                        .system(systemPrompt)
                        .messages(messages)
                        .tools(tools)
                        .build();

                // Stream the response, accumulating into a full Message for tool processing.
                // Text deltas and ToolStart events are buffered and flushed after the stream closes,
                // once we know whether this iteration uses tools (thinking) or is the final answer.
                // This preserves correct ordering: thinking text → tool_start → tool_end.
                MessageAccumulator accumulator = MessageAccumulator.create();
                List<String> textBuffer = new ArrayList<>();
                List<ChatEvent.ToolStart> toolStartBuffer = new ArrayList<>();
                long startNs = System.nanoTime();

                try (StreamResponse<RawMessageStreamEvent> stream =
                             client.messages().createStreaming(params)) {
                    stream.stream().forEach(event -> {
                        accumulator.accumulate(event);

                        // Buffer text deltas — type (thinking vs text) is resolved after the stream closes
                        if (event.isContentBlockDelta()) {
                            var delta = event.asContentBlockDelta().delta();
                            if (delta.isText()) {
                                String text = delta.asText().text();
                                if (text != null && !text.isEmpty()) {
                                    textBuffer.add(text);
                                }
                            }
                        }

                        // Buffer ToolStart events so they are emitted after the thinking text
                        if (event.isContentBlockStart()) {
                            var block = event.asContentBlockStart().contentBlock();
                            if (block.isToolUse()) {
                                ToolUseBlock toolUse = block.asToolUse();
                                Map<String, Object> inputMap = convertJsonValueToMap(toolUse._input());
                                toolStartBuffer.add(new ChatEvent.ToolStart(toolUse.name(), inputMap));
                            }
                        }
                    });
                }

                long durationMs = (System.nanoTime() - startNs) / 1_000_000;
                Message response = accumulator.message();

                logUsage(response, iteration + 1);
                tokenBudgetTracker.recordUsage(
                        response.usage().inputTokens(),
                        response.usage().outputTokens());

                // Process tool calls from the accumulated message
                List<ToolUseBlock> toolUseBlocks = response.content().stream()
                        .filter(ContentBlock::isToolUse)
                        .map(ContentBlock::asToolUse)
                        .toList();

                // Flush thinking/text deltas first, then tool_start events — preserving logical order
                boolean isThinkingIteration = !toolUseBlocks.isEmpty();
                for (String chunk : textBuffer) {
                    if (isThinkingIteration) {
                        eventSink.accept(new ChatEvent.ThinkingDelta(chunk));
                    } else {
                        eventSink.accept(new ChatEvent.TextDelta(chunk));
                    }
                }
                toolStartBuffer.forEach(eventSink);

                List<ContentBlockParam> assistantBlocks = new ArrayList<>();
                List<ContentBlockParam> toolResults = new ArrayList<>();
                List<String> toolNamesList = new ArrayList<>();

                for (ContentBlock block : response.content()) {
                    if (block.isText()) {
                        assistantBlocks.add(ContentBlockParam.ofText(block.asText().toParam()));
                    } else if (block.isToolUse()) {
                        ToolUseBlock tb = block.asToolUse();
                        if (tb._input() instanceof JsonMissing) {
                            // Anthropic requires input to be present; substitute empty object
                            assistantBlocks.add(ContentBlockParam.ofToolUse(
                                    ToolUseBlockParam.builder()
                                            .id(tb.id())
                                            .name(tb.name())
                                            .input(ToolUseBlockParam.Input.builder().build())
                                            .build()));
                        } else {
                            assistantBlocks.add(ContentBlockParam.ofToolUse(tb.toParam()));
                        }
                    }
                }

                for (ToolUseBlock toolUse : toolUseBlocks) {
                    toolNamesList.add(toolUse.name());
                    LOG.infof("Streaming tool call: %s (id=%s)", toolUse.name(), toolUse.id());
                    String result = dispatchTool(toolUse, workspace);

                    eventSink.accept(new ChatEvent.ToolEnd(toolUse.name(), result));

                    boolean isError = result.startsWith("ERROR:");
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(result)
                                    .isError(isError)
                                    .build()
                    ));
                }

                Usage usage = response.usage();
                String stopReason = response.stopReason().map(Object::toString).orElse(null);
                String toolNamesCsv = toolNamesList.isEmpty() ? null : String.join(",", toolNamesList);

                aiCallStore.save(new AiCallRecord(
                        null, jobId, jobType, modelName, iteration + 1,
                        usage.inputTokens(), usage.outputTokens(),
                        usage.cacheCreationInputTokens().orElse(0L),
                        usage.cacheReadInputTokens().orElse(0L),
                        stopReason, toolNamesCsv, durationMs,
                        false, null, Instant.now(),
                        null, null));

                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(assistantBlocks)
                        .build());

                if (toolUseBlocks.isEmpty()) {
                    LOG.infof("Streaming agent finished after %d iterations", iteration + 1);
                    eventSink.accept(new ChatEvent.Done(jobId));
                    return;
                }

                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(toolResults)
                        .build());
            }

            LOG.warnf("Streaming agent hit max iterations (%d)", iterationCap);
            eventSink.accept(new ChatEvent.Error("Max iterations reached without completing"));

        } catch (Exception e) {
            LOG.errorf("Streaming agent loop error: %s", e.getMessage());
            eventSink.accept(new ChatEvent.Error(e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    private void logUsage(Message response, int iteration) {
        Usage usage = response.usage();
        long input = usage.inputTokens();
        long output = usage.outputTokens();
        long cacheWrite = usage.cacheCreationInputTokens().orElse(0L);
        long cacheRead = usage.cacheReadInputTokens().orElse(0L);
        LOG.infof("Iteration %d tokens — input: %d, output: %d, cache_write: %d, cache_read: %d",
                iteration, input, output, cacheWrite, cacheRead);
    }

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 5_000;

    private Message callWithRetry(MessageCreateParams params) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                tokenBudgetTracker.waitIfNeeded();
                return client.messages().create(params);
            } catch (RateLimitException e) {
                if (attempt == MAX_RETRIES) {
                    throw e;
                }
                long waitMs = INITIAL_BACKOFF_MS * (1L << attempt);
                // Add up to 50% jitter to prevent thundering herd when multiple jobs retry together
                waitMs += (long) (waitMs * 0.5 * ThreadLocalRandom.current().nextDouble());
                LOG.warnf("Rate limited (attempt %d/%d), waiting %dms before retry...",
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

    private String dispatchTool(ToolUseBlock toolUse, WorkspaceContext workspace) {
        ToolExecutor executor = toolRegistry.get(toolUse.name());
        if (executor == null) {
            return "ERROR: Unknown tool: " + toolUse.name();
        }

        try {
            JsonValue inputJson = toolUse._input();
            Map<String, Object> inputMap = convertJsonValueToMap(inputJson);
            return executor.execute(workspace, inputMap);
        } catch (Exception e) {
            LOG.errorf("Tool execution error for %s: %s", toolUse.name(), e.getMessage());
            return "ERROR: Tool execution failed: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertJsonValueToMap(JsonValue json) {
        Object raw = json.accept(new JsonValue.Visitor<Object>() {
            @Override public Object visitMissing() { return null; }
            @Override public Object visitNull() { return null; }
            @Override public Object visitBoolean(boolean value) { return value; }
            @Override public Object visitNumber(Number value) { return value; }
            @Override public Object visitString(String value) { return value; }
            @Override public Object visitArray(List<? extends JsonValue> values) {
                return values.stream().map(v -> v.accept(this)).toList();
            }
            @Override public Object visitObject(Map<String, ? extends JsonValue> values) {
                Map<String, Object> result = new HashMap<>();
                values.forEach((k, v) -> result.put(k, v.accept(this)));
                return result;
            }
        });

        if (raw instanceof Map) {
            return (Map<String, Object>) raw;
        }
        return Map.of();
    }
}
