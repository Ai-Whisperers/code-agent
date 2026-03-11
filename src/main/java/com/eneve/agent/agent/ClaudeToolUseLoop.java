package com.eneve.agent.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
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

    @ConfigProperty(name = "anthropic.api.key")
    String apiKey;

    @ConfigProperty(name = "anthropic.model", defaultValue = "claude-sonnet-4-20250514")
    String modelName;

    @ConfigProperty(name = "anthropic.max-tokens", defaultValue = "8192")
    long maxTokens;

    @ConfigProperty(name = "run-fix.max-loop-iterations", defaultValue = "50")
    int maxIterations;

    @Inject
    ToolRegistry toolRegistry;

    /**
     * Run the agentic tool-use loop.
     *
     * @param systemPrompt the assembled system prompt (rules + guardrails + task)
     * @param workspace    the isolated workspace for this job
     * @return the final text summary from Claude
     */
    public String run(String systemPrompt, WorkspaceContext workspace) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content("Please complete the task described in the system prompt. "
                        + "Start by listing the repository structure, then proceed.")
                .build());

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            LOG.infof("Agent loop iteration %d/%d", iteration + 1, maxIterations);

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.of(modelName))
                    .maxTokens(maxTokens)
                    .system(systemPrompt)
                    .messages(messages)
                    .tools(ToolDefinitions.all())
                    .build();

            Message response = client.messages().create(params);

            boolean hasToolUse = false;
            List<ContentBlockParam> toolResults = new ArrayList<>();
            StringBuilder textAccumulator = new StringBuilder();
            List<ContentBlockParam> assistantBlocks = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (block.isText()) {
                    textAccumulator.append(block.asText().text());
                    assistantBlocks.add(ContentBlockParam.ofText(block.asText().toParam()));
                } else if (block.isToolUse()) {
                    hasToolUse = true;
                    ToolUseBlock toolUse = block.asToolUse();

                    LOG.infof("Tool call: %s (id=%s)", toolUse.name(), toolUse.id());

                    String result = dispatchTool(toolUse, workspace);
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

        LOG.warnf("Agent loop hit max iterations (%d)", maxIterations);
        return "Agent loop reached maximum iterations without completing. Partial work may exist.";
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
