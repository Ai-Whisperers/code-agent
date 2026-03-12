package com.eneve.agent.agent;

import java.util.List;
import java.util.Locale;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Classifies the intent of a developer's reply to an agent review comment.
 * Supports an explicit /fix command as a fast path, and falls back to a
 * lightweight Claude call for ambiguous messages.
 */
@ApplicationScoped
public class IntentClassifier {

    private static final Logger LOG = Logger.getLogger(IntentClassifier.class);

    @ConfigProperty(name = "anthropic.api.key")
    String apiKey;

    @ConfigProperty(name = "anthropic.model", defaultValue = "claude-sonnet-4-20250514")
    String modelName;

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

        // AI classification fallback
        try {
            return classifyWithClaude(trimmed, originalFinding);
        } catch (Exception e) {
            LOG.warnf("Intent classification failed, defaulting to DISCUSS: %s", e.getMessage());
            return CommentIntent.DISCUSS;
        }
    }

    private CommentIntent classifyWithClaude(String humanMessage, String originalFinding) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        String prompt = """
                Given an AI code reviewer's finding and a developer's reply, classify the developer's intent.

                Finding: %s
                Reply: %s

                Is the developer requesting that the suggested code change be implemented/applied, \
                or are they asking a question / having a discussion?

                Respond with exactly one word: FIX or DISCUSS
                """.formatted(
                originalFinding != null ? originalFinding : "(unknown)",
                humanMessage
        );

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(50)
                .messages(List.of(
                        MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content(prompt)
                                .build()
                ))
                .build();

        Message response = client.messages().create(params);

        String responseText = "";
        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                responseText = block.asText().text().trim();
                break;
            }
        }

        LOG.infof("Intent classification response: '%s'", responseText);

        if (responseText.toUpperCase(Locale.ROOT).contains("FIX")) {
            return CommentIntent.FIX;
        }
        return CommentIntent.DISCUSS;
    }
}
