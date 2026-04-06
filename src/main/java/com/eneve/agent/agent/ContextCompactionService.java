package com.eneve.agent.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.eneve.agent.agent.service.PromptTemplateService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Compacts a growing conversation history by asking Claude to produce a structured
 * 9-section summary of the session so far, then replacing the older messages with
 * that summary while keeping the most recent exchanges verbatim.
 *
 * <p>The summarization call uses the <em>actual</em> {@code MessageParam} list as
 * its context so Claude can see real tool results (file contents, grep output, etc.)
 * rather than a flattened text representation where every tool result is hidden.
 * Two synthetic turns are appended to maintain the required alternating role order:
 * <ol>
 *   <li>ASSISTANT — compaction preamble (prevents a double-USER at the end)</li>
 *   <li>USER — the structured compact prompt (loaded from the {@code context-compaction}
 *       prompt template, editable via the Prompts page)</li>
 * </ol>
 *
 * <p>The {@code <analysis>} scratchpad block in the response is stripped before the
 * summary is injected into the new history; only the {@code <summary>} section is kept.
 *
 * <p>The resulting compacted history is:
 * <ol>
 *   <li>USER — framing message + extracted summary</li>
 *   <li>Tail: up to {@value #TAIL_WINDOW} recent messages, beginning from the first
 *       USER message (guarantees no consecutive-role violations).</li>
 * </ol>
 */
@ApplicationScoped
public class ContextCompactionService {

    private static final Logger LOG = Logger.getLogger(ContextCompactionService.class);

    private static final Pattern ANALYSIS_PATTERN =
            Pattern.compile("(?s)<analysis>.*?</analysis>");
    private static final Pattern SUMMARY_PATTERN =
            Pattern.compile("(?s)<summary>(.*?)</summary>");

    /**
     * Minimum max-tokens budget for the summary response. Using a value larger than
     * the normal job max-tokens ensures the summary has room to be thorough even when
     * the job is configured with a tight budget.
     */
    private static final long SUMMARY_MAX_TOKENS = 16_384;

    /**
     * Number of recent messages from the original history to inspect when building
     * the tail. The tail begins at the first USER message within this window so that
     * role alternation is always valid.
     */
    private static final int TAIL_WINDOW = 8;

    @ConfigProperty(name = "agent.context.window-size", defaultValue = "200000")
    long contextWindowSize;

    @ConfigProperty(name = "agent.context.compaction-threshold-pct", defaultValue = "0.75")
    double compactionThresholdPct;

    @Inject
    AnthropicClient client;

    @Inject
    PromptTemplateService promptTemplateService;

    @Inject
    TokenBudgetTracker tokenBudgetTracker;

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns the input-token count at which compaction should trigger.
     *
     * <p>Computed as {@code floor(windowSize × pct) - maxTokens}, where the
     * {@code - maxTokens} term reserves headroom for the next model response.
     * Controlled by:
     * <ul>
     *   <li>{@code agent.context.window-size} (default 200 000)</li>
     *   <li>{@code agent.context.compaction-threshold-pct} (default 0.75)</li>
     * </ul>
     *
     * @param maxTokens the job's configured max-output-tokens (used for headroom)
     */
    public long compactionThreshold(long maxTokens) {
        return Math.max(0L, (long) (contextWindowSize * compactionThresholdPct) - maxTokens);
    }

    /**
     * Summarises {@code messages} and returns a compacted replacement list.
     *
     * <p>Never swallows exceptions — on failure the caller is expected to increment
     * its consecutive-failure counter and optionally circuit-break.
     *
     * @param messages     current message history (must end with a USER message)
     * @param systemPrompt the job's system prompt (passed to the summary call)
     * @param modelName    model to use for summarisation
     * @param maxTokens    normal max-tokens for the job (used to floor the summary budget)
     * @return compacted message list (always a new list, never the same reference)
     * @throws Exception if the Claude API call fails or returns an empty response
     */
    public List<MessageParam> compact(List<MessageParam> messages, String systemPrompt,
                                      String modelName, long maxTokens) throws Exception {
        LOG.infof("Context compaction triggered: %d messages in history — summarizing", messages.size());

        String promptText = promptTemplateService.resolve(
                "context-compaction", Map.of("CUSTOM_INSTRUCTIONS", ""));

        // Build the call with the real message history in context so Claude sees actual
        // tool results.  Append a synthetic ASSISTANT preamble so the final message is
        // ASSISTANT → USER (compact prompt), maintaining alternating-role invariant.
        List<MessageParam> compactMsgs = new ArrayList<>(messages);
        compactMsgs.add(MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .content("I will now produce a structured summary of this session.")
                .build());
        compactMsgs.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(promptText)
                .build());

        long summaryMaxTokens = Math.max(maxTokens, SUMMARY_MAX_TOKENS);
        MessageCreateParams summaryParams = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(summaryMaxTokens)
                .system(systemPrompt)
                .messages(compactMsgs)
                // No tools — the compact prompt explicitly forbids tool use.
                .build();

        tokenBudgetTracker.waitIfNeeded();
        Message summaryResponse = client.messages().create(summaryParams);
        tokenBudgetTracker.recordUsage(
                summaryResponse.usage().inputTokens(),
                summaryResponse.usage().outputTokens());

        String rawText = summaryResponse.content().stream()
                .filter(ContentBlock::isText)
                .map(b -> b.asText().text())
                .collect(Collectors.joining());

        if (rawText.isBlank()) {
            throw new IllegalStateException("Compaction API call returned an empty response");
        }

        String summary = extractSummary(rawText);

        // ── Build compacted history ──────────────────────────────────────────
        List<MessageParam> compressed = new ArrayList<>();

        // 1. Summary framing as USER (the first message in the new history).
        compressed.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content("This session is being continued from a previous conversation that ran out of context. "
                        + "The summary below covers the earlier portion.\n\n" + summary)
                .build());

        // 2. Tail: up to TAIL_WINDOW recent messages, starting from the first USER message
        //    so we never produce consecutive ASSISTANT turns after the summary.
        int tailStart = Math.max(0, messages.size() - TAIL_WINDOW);
        while (tailStart < messages.size()
                && messages.get(tailStart).role() != MessageParam.Role.USER) {
            tailStart++;
        }
        compressed.addAll(messages.subList(tailStart, messages.size()));

        LOG.infof("Context compacted: %d messages → %d messages", messages.size(), compressed.size());
        return compressed;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Strips the {@code <analysis>} scratchpad and extracts the content of
     * {@code <summary>}. Falls back to the full response if no XML tags are present
     * (graceful degradation for models that ignore the format instruction).
     */
    private static String extractSummary(String raw) {
        String text = ANALYSIS_PATTERN.matcher(raw).replaceAll("").trim();
        Matcher m = SUMMARY_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return text;
    }
}
