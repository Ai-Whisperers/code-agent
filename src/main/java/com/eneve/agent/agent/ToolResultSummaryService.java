package com.eneve.agent.agent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replaces large, old tool-result blocks in the message history with compact one-liner
 * summaries to reduce the input-token count on subsequent API calls.
 *
 * <p>Only results older than {@code agent.tool-summary.grace-turns} turns are eligible;
 * the most recent turns are always kept verbatim so Claude has full detail for its
 * active reasoning. No LLM calls are made — summaries are generated heuristically from
 * the tool name and input arguments.
 *
 * <p>The three config keys (all read via {@link SettingsService}) are:
 * <ul>
 *   <li>{@code agent.tool-summary.enabled} — master switch (default: {@code true})</li>
 *   <li>{@code agent.tool-summary.grace-turns} — turns to keep verbatim (default: {@code 3})</li>
 *   <li>{@code agent.tool-summary.threshold-chars} — minimum result length to summarize
 *       (default: {@code 2000})</li>
 * </ul>
 */
@ApplicationScoped
public class ToolResultSummaryService {

    private static final Logger LOG = Logger.getLogger(ToolResultSummaryService.class);

    @Inject
    SettingsService settings;

    // ── Public API ───────────────────────────────────────────────────────────

    /** Returns {@code true} when the feature is enabled via settings. */
    public boolean isEnabled() {
        return Boolean.parseBoolean(settings.get("agent.tool-summary.enabled", "true"));
    }

    /**
     * Returns a new message list in which oversized tool-result blocks older than the
     * configured grace window have been replaced by compact one-liner summaries.
     *
     * <p>The original {@code messages} list is never mutated; a fresh list is always returned.
     *
     * @param messages the current conversation history
     * @return a new list with old, large tool results summarized
     */
    public List<MessageParam> summarizeOldResults(List<MessageParam> messages) {
        int graceTurns = Integer.parseInt(settings.get("agent.tool-summary.grace-turns", "3"));
        int thresholdChars = Integer.parseInt(settings.get("agent.tool-summary.threshold-chars", "2000"));

        // Each turn contributes 2 messages (ASSISTANT + USER tool-result).
        // Keep the last graceTurns×2 messages verbatim.
        int protectedFrom = Math.max(0, messages.size() - graceTurns * 2);

        // Build toolUseId → ToolInfo map by scanning every ASSISTANT message.
        Map<String, ToolInfo> toolInfoByUseId = buildToolInfoMap(messages);

        List<MessageParam> result = new ArrayList<>(messages.size());
        int summarizedCount = 0;

        for (int i = 0; i < messages.size(); i++) {
            MessageParam msg = messages.get(i);

            // Leave messages in the protected tail as-is.
            if (i >= protectedFrom) {
                result.add(msg);
                continue;
            }

            // Only USER messages that carry tool results need inspection.
            if (msg.role() != MessageParam.Role.USER || !msg.content().isBlockParams()) {
                result.add(msg);
                continue;
            }

            List<ContentBlockParam> blocks = msg.content().asBlockParams();
            boolean anyReplaced = false;
            List<ContentBlockParam> updatedBlocks = new ArrayList<>(blocks.size());

            for (ContentBlockParam block : blocks) {
                if (!block.isToolResult()) {
                    updatedBlocks.add(block);
                    continue;
                }

                ToolResultBlockParam tr = block.asToolResult();
                Optional<ToolResultBlockParam.Content> contentOpt = tr.content();
                if (contentOpt.isEmpty() || !contentOpt.get().isString()) {
                    // Non-string content (e.g. image blocks) — leave untouched.
                    updatedBlocks.add(block);
                    continue;
                }

                String raw = contentOpt.get().asString();
                if (raw.length() <= thresholdChars) {
                    updatedBlocks.add(block);
                    continue;
                }

                // Replace with a compact summary.
                ToolInfo info = toolInfoByUseId.get(tr.toolUseId());
                String summary = buildSummary(
                        info != null ? info.name() : "unknown",
                        info != null ? info.input() : Map.of(),
                        raw);

                ToolResultBlockParam summarized = tr.toBuilder()
                        .content(summary)
                        .build();
                updatedBlocks.add(ContentBlockParam.ofToolResult(summarized));
                anyReplaced = true;
                summarizedCount++;
            }

            if (anyReplaced) {
                result.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(updatedBlocks)
                        .build());
            } else {
                result.add(msg);
            }
        }

        if (summarizedCount > 0) {
            LOG.debugf("Tool-result summarization: replaced %d large result(s) in %d messages",
                    summarizedCount, messages.size());
        }
        return result;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Scans all ASSISTANT messages and builds a map from each tool-use ID to its
     * name + input parameters, so that a tool result can be annotated with the
     * name of the tool that produced it.
     */
    private static Map<String, ToolInfo> buildToolInfoMap(List<MessageParam> messages) {
        Map<String, ToolInfo> map = new HashMap<>();
        for (MessageParam msg : messages) {
            if (msg.role() != MessageParam.Role.ASSISTANT || !msg.content().isBlockParams()) {
                continue;
            }
            for (ContentBlockParam block : msg.content().asBlockParams()) {
                if (!block.isToolUse()) continue;
                ToolUseBlockParam tu = block.asToolUse();
                Map<String, String> input = extractStringInputs(tu);
                map.put(tu.id(), new ToolInfo(tu.name(), input));
            }
        }
        return map;
    }

    /**
     * Extracts scalar entries from a tool-use block's input map as strings.
     * Uses the {@link JsonValue.Visitor} pattern (same approach as
     * {@code ClaudeToolUseLoop.convertJsonValueToMap}) to avoid raw-type issues.
     * Nested objects and arrays are skipped — only scalars are needed for labels.
     */
    private static Map<String, String> extractStringInputs(ToolUseBlockParam tu) {
        Map<String, String> out = new HashMap<>();
        tu.input()._additionalProperties().forEach((k, v) -> {
            String strVal = jsonValueToString(v);
            if (strVal != null) {
                out.put(k, strVal);
            }
        });
        return out;
    }

    private static String jsonValueToString(JsonValue v) {
        return v.accept(new JsonValue.Visitor<>() {
            @Override public String visitMissing()                               { return null; }
            @Override public String visitNull()                                  { return null; }
            @Override public String visitBoolean(boolean value)                  { return Boolean.toString(value); }
            @Override public String visitNumber(Number value)                    { return value.toString(); }
            @Override public String visitString(String value)                    { return value; }
            @Override public String visitArray(List<? extends JsonValue> values) { return null; }
            @Override public String visitObject(Map<String, ? extends JsonValue> values) { return null; }
        });
    }

    /**
     * Generates a compact one-liner summary for a tool result.
     * Tool-specific templates extract the most useful label (path, query, command, …).
     * All templates include the character count so the model knows what was compressed.
     */
    static String buildSummary(String toolName, Map<String, String> input, String content) {
        int chars = content.length();
        int lines = countLines(content);

        return switch (toolName) {
            case "read_file" -> {
                String path = input.getOrDefault("path", "?");
                yield "Read " + path + " (" + lines + " lines)";
            }
            case "write_file" -> {
                String path = input.getOrDefault("path", "?");
                yield "Wrote " + path;
            }
            case "run_command" -> {
                String cmd = truncateLabel(input.getOrDefault("command", "?"), 60);
                yield "Ran `" + cmd + "` — " + lines + " lines output";
            }
            case "search_code" -> {
                String query = truncateLabel(input.getOrDefault("query", "?"), 50);
                int matchCount = countOccurrences(content, "\n");
                yield "Searched '" + query + "' — " + matchCount + " matches";
            }
            case "list_files" -> {
                String path = input.getOrDefault("path", ".");
                int entries = lines;
                yield "Listed " + path + " — " + entries + " entries";
            }
            case "fetch_url" -> {
                String url = truncateLabel(input.getOrDefault("url", "?"), 80);
                yield "Fetched " + url + " — " + chars + " chars";
            }
            case "web_search" -> {
                String query = truncateLabel(input.getOrDefault("query", "?"), 50);
                yield "Web search '" + query + "' — " + chars + " chars";
            }
            case "semantic_search" -> {
                String query = truncateLabel(input.getOrDefault("query", "?"), 50);
                yield "Semantic search '" + query + "' — " + chars + " chars";
            }
            case "query_code_graph" -> {
                String query = truncateLabel(
                        input.getOrDefault("query", input.getOrDefault("symbol", "?")), 50);
                yield "Code graph query '" + query + "' — " + chars + " chars";
            }
            default -> {
                if (toolName.startsWith("jira_")) {
                    String key = input.getOrDefault("issue_key",
                            input.getOrDefault("project_key",
                            input.getOrDefault("query", "")));
                    String label = key.isBlank() ? toolName : toolName + "(" + key + ")";
                    yield label + " — " + chars + " chars";
                }
                if (toolName.startsWith("confluence_")) {
                    String key = input.getOrDefault("space_key",
                            input.getOrDefault("query",
                            input.getOrDefault("page_id", "")));
                    String label = key.isBlank() ? toolName : toolName + "(" + key + ")";
                    yield label + " — " + chars + " chars";
                }
                if (toolName.startsWith("aws_")) {
                    String region = input.getOrDefault("region", "");
                    String label = region.isBlank() ? toolName : toolName + "(" + region + ")";
                    yield label + " — " + chars + " chars";
                }
                yield toolName + " — " + chars + " chars output";
            }
        };
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        return count;
    }

    private static int countOccurrences(String s, String sub) {
        if (s == null || s.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static String truncateLabel(String s, int maxLen) {
        if (s == null) return "?";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    // ── Value types ──────────────────────────────────────────────────────────

    private record ToolInfo(String name, Map<String, String> input) {}
}
