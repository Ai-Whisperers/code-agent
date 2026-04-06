package com.eneve.agent.agent;

import java.time.Instant;
import com.eneve.agent.agent.model.AiCallRecord;
import com.eneve.agent.agent.model.CommentIntent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Usage;

import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.store.AiCallStore;
import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Classifies the intent of a developer's reply to an agent review comment.
 * Supports an explicit /fix command as a fast path, and falls back to a
 * lightweight Claude call for ambiguous messages.
 */
@ApplicationScoped
public class IntentClassifier {

    private static final Set<String> FIX_KEYWORDS = Set.of(
            "please fix", "apply this", "apply the fix", "go ahead",
            "do it", "yes fix", "fix it", "make the change", "apply suggestion");

    private static final Logger LOG = Logger.getLogger(IntentClassifier.class);

    @Inject
    AnthropicClient client;

    @Inject
    AiCallStore aiCallStore;

    @Inject
    PromptTemplateService promptTemplates;

    @Inject
    SettingsService settings;

    private String fastModelName() {
        return settings.get("anthropic.fast-model", "claude-haiku-4-5");
    }

    /**
     * Classify the developer's reply. Returns FIX if the developer is requesting
     * the agent to apply the suggested code change, DISCUSS otherwise.
     */
    public CommentIntent classify(String humanMessage, String originalFinding) {
        if (humanMessage == null || humanMessage.isBlank()) {
            return CommentIntent.DISCUSS;
        }

        String trimmed = humanMessage.trim();

        // Fast path: explicit /fix command
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("/fix")) {
            LOG.infof("Intent: FIX (explicit /fix command)");
            return CommentIntent.FIX;
        }

        // Fast path: unambiguous fix-intent keywords
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (FIX_KEYWORDS.stream().anyMatch(lower::contains)) {
            LOG.infof("Intent: FIX (keyword match)");
            return CommentIntent.FIX;
        }

        // AI classification fallback
        try {
            return classifyWithClaude(trimmed, originalFinding);
        } catch (Exception e) {
            LOG.warnf("Intent classification failed, defaulting to DISCUSS: %s", e.getMessage());
            return CommentIntent.DISCUSS;
        }
    }

    private CommentIntent classifyWithClaude(String humanMessage, String originalFinding) {
        String prompt = promptTemplates.resolve("intent-classifier", Map.of(
                "FINDING", originalFinding != null ? originalFinding : "(unknown)",
                "REPLY", humanMessage
        ));

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(fastModelName()))
                .maxTokens(50)
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
                    null, null, "INTENT_CLASSIFICATION", fastModelName(), null,
                    0, 0, 0, 0,
                    null, null, durationMs,
                    true, e.getMessage(), Instant.now(),
                    prompt, null,
                    null, 0));
            throw e;
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

        String responseText = "";
        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                responseText = block.asText().text().trim();
                break;
            }
        }

        Usage usage = response.usage();
        String stopReason = response.stopReason().map(sr -> sr.toString()).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, null, "INTENT_CLASSIFICATION", fastModelName(), null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now(),
                prompt, responseText.isBlank() ? null : responseText,
                null, 0));

        LOG.infof("Intent classification response: '%s'", responseText);

        if (responseText.toUpperCase(Locale.ROOT).contains("FIX")) {
            return CommentIntent.FIX;
        }
        return CommentIntent.DISCUSS;
    }
}
