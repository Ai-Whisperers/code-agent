package com.eneve.agent.planner;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Usage;
import com.eneve.agent.agent.AiCallRecord;
import com.eneve.agent.agent.AiCallStore;
import com.eneve.agent.agent.PromptTemplateService;
import com.eneve.agent.util.UrlUtils;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Generates an AI-powered execution plan from a specification.
 * Uses a single Claude call (no tool-use loop) to produce a markdown plan document.
 * Follows the same single-call pattern as PrSummaryGenerator.
 */
@ApplicationScoped
public class PlannerService {

    private static final Logger LOG = Logger.getLogger(PlannerService.class);
    @ConfigProperty(name = "anthropic.model", defaultValue = "claude-sonnet-4-20250514")
    String modelName;

    @ConfigProperty(name = "planner.max-tokens", defaultValue = "8192")
    long maxTokens;

    @Inject
    AnthropicClient client;

    @Inject
    AiCallStore aiCallStore;

    @Inject
    PromptTemplateService promptTemplates;

    /**
     * Generate an execution plan from a specification text.
     *
     * @param specText     the full specification (Jira ticket text, free text, etc.)
     * @param repoUrl      the target repository URL (optional)
     * @param targetBranch the base branch (e.g. "main")
     * @param sourceType   JIRA, FREE_TEXT, CHAT, or URL
     * @param sourceRef    JIRA key, conversation ID, or null
     * @return a DRAFT ExecutionPlan with markdown content, or null on failure
     */
    public ExecutionPlan generatePlan(String specText, String repoUrl,
                                      String targetBranch, String sourceType, String sourceRef) {
        if (specText == null || specText.isBlank()) {
            LOG.warn("PlannerService: empty spec text, cannot generate plan");
            return null;
        }

        String safeRepoUrl = UrlUtils.stripCredentials(repoUrl);

        String prompt = buildPrompt(specText, safeRepoUrl, targetBranch);
        String planId = "plan-" + UUID.randomUUID();

        String markdownContent = callClaude(prompt, planId);
        if (markdownContent == null) {
            return null;
        }

        String title = deriveTitle(specText, sourceRef);
        Instant now = Instant.now();

        return new ExecutionPlan(
                planId,
                PlanStatus.DRAFT.name(),
                sourceType != null ? sourceType : "FREE_TEXT",
                sourceRef,
                safeRepoUrl,
                targetBranch != null ? targetBranch : "main",
                title,
                new PlanData(List.of()),
                now,
                now,
                null,
                null,
                null,
                null,
                null, // conversationId
                markdownContent,
                null  // workspacePath
        );
    }

    private String buildPrompt(String specText, String repoUrl, String targetBranch) {
        return promptTemplates.resolve("planner", Map.of(
                "REPO_URL", repoUrl != null ? repoUrl : "(unspecified)",
                "TARGET_BRANCH", targetBranch != null ? targetBranch : "main",
                "SPEC", specText
        ));
    }

    private String callClaude(String prompt, String planId) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(maxTokens)
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
                    null, planId, "PLAN", modelName, null,
                    0, 0, 0, 0,
                    null, null, durationMs,
                    true, e.getMessage(), Instant.now(),
                    prompt, null));
            LOG.errorf("PlannerService Claude call failed: %s", e.getMessage());
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
        String stopReason = response.stopReason().map(Object::toString).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, planId, "PLAN", modelName, null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now(),
                prompt, responseText));

        LOG.infof("Plan generated — tokens: in=%d, out=%d, duration=%dms",
                usage.inputTokens(), usage.outputTokens(), durationMs);

        return responseText;
    }

    private String deriveTitle(String specText, String sourceRef) {
        if (sourceRef != null && !sourceRef.isBlank()) {
            return sourceRef;
        }
        String firstLine = specText.lines().findFirst().orElse("").strip();
        return firstLine.length() > 80 ? firstLine.substring(0, 77) + "..." : firstLine;
    }
}
