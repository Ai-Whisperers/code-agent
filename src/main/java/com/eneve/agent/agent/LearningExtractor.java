package com.eneve.agent.agent;

import java.time.Instant;
import com.eneve.agent.agent.model.AiCallRecord;
import com.eneve.agent.agent.model.MemoryEntry;
import com.eneve.agent.agent.model.CommentContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Usage;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.store.AiCallStore;
import com.eneve.agent.agent.store.MemoryStore;
import com.eneve.agent.scm.ThreadComment;
import com.eneve.agent.settings.SettingsService;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Examines a review conversation thread and extracts any generalizable team
 * preference or coding convention that should be remembered for future reviews.
 * <p>
 * Uses a lightweight Claude call (similar to {@link IntentClassifier}) and
 * persists the extracted learning via {@link MemoryStore}.
 */
@ApplicationScoped
public class LearningExtractor {

    private static final Logger LOG = Logger.getLogger(LearningExtractor.class);

    @Inject AnthropicClient client;
    @Inject
    AiCallStore aiCallStore;
    @Inject
    MemoryStore memoryStore;
    @Inject
    PromptTemplateService promptTemplates;
    @Inject
    SettingsService settingsService;

    /**
     * Analyse the conversation thread and, if it contains a generalizable
     * team preference, store it as a memory entry for the repository.
     *
     * @return the extracted preference text, or empty if nothing worth remembering
     */
    public Optional<String> extractAndStore(List<ThreadComment> thread,
                                            CommentContext ctx,
                                            String workspace,
                                            String repoSlug,
                                            String developerUsername) {
        if (thread == null || thread.isEmpty()) {
            return Optional.empty();
        }

        Optional<String> learning = extractLearning(thread, ctx);
        if (learning.isEmpty()) {
            return Optional.empty();
        }

        String memoryText = learning.get();

        if (memoryStore.exists(workspace, repoSlug, memoryText)) {
            LOG.debugf("Duplicate learning skipped for %s/%s: %s", workspace, repoSlug, memoryText);
            return Optional.of(memoryText);
        }

        Long sourceCommentId = thread.stream()
                .filter(tc -> !tc.isAgent())
                .map(ThreadComment::id)
                .findFirst()
                .orElse(null);

        MemoryEntry entry = MemoryEntry.extracted(
                workspace, repoSlug, memoryText,
                ctx.category(), sourceCommentId, ctx.prId(), developerUsername);
        memoryStore.save(entry);

        LOG.infof("Extracted learning for %s/%s: %s", workspace, repoSlug, memoryText);
        return Optional.of(memoryText);
    }

    private Optional<String> extractLearning(List<ThreadComment> thread, CommentContext ctx) {
        String fastModelName = settingsService.get("anthropic.fast-model", "claude-haiku-4-5");
        StringBuilder conversationText = new StringBuilder();
        for (ThreadComment tc : thread) {
            String role = tc.isAgent() ? "AI Reviewer" : "Developer (" + tc.author() + ")";
            conversationText.append(role).append(": ").append(tc.content()).append("\n\n");
        }

        String prompt = promptTemplates.resolve("learning-extractor", Map.of(
                "FILE", ctx.filePath() != null ? ctx.filePath() : "(general)",
                "LINE", String.valueOf(ctx.line()),
                "CATEGORY", ctx.category() != null ? ctx.category() : "General",
                "FINDING", ctx.findingText() != null ? ctx.findingText() : "(unknown)",
                "CONVERSATION", conversationText.toString()
        ));

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(fastModelName))
                .maxTokens(200)
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
                    null, null, "LEARNING_EXTRACTION", fastModelName, null,
                    0, 0, 0, 0,
                    null, null, durationMs,
                    true, e.getMessage(), Instant.now(),
                    prompt, null,
                    null, 0));
            LOG.warnf("Learning extraction failed: %s", e.getMessage());
            return Optional.empty();
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
        String stopReason = response.stopReason().map(Object::toString).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, null, "LEARNING_EXTRACTION", fastModelName, null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now(),
                prompt, responseText.isBlank() ? null : responseText,
                null, 0));

        LOG.debugf("Learning extraction response: '%s'", responseText);

        if (responseText.equalsIgnoreCase("NONE") || responseText.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(responseText);
    }
}
