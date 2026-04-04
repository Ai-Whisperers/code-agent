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
import com.eneve.agent.agent.model.AiCallRecord;
import com.eneve.agent.agent.store.AiCallStore;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.util.UrlUtils;

import com.eneve.agent.settings.SettingsService;
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

    @Inject
    SettingsService settings;

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
                null, // sourceRepoUrl — set by generateRewritePlan for REWRITE plans
                targetBranch != null ? targetBranch : "main",
                title,
                new PlanData(List.of()),
                now,
                now,
                null,
                null,
                null,
                null,
                null,
                markdownContent,
                null,
                false,
                null // createdBy set by PlanResource after generation
        );
    }

    /**
     * Generate an execution plan for a cross-language rewrite, framework migration, or code extraction.
     *
     * @param specText        the full specification describing the rewrite goal
     * @param sourceRepoUrl   the source repository URL (read-only reference)
     * @param targetRepoUrl   the target repository URL (where the rewritten code will live)
     * @param sourceLanguage  source language/framework hint (e.g. "php/laravel")
     * @param targetLanguage  target language/framework hint (e.g. "dotnet/csharp")
     * @param rewriteMode     "full_rewrite", "framework_migration", or "extraction" (defaults to full_rewrite)
     * @param scopeHint       for extraction mode: description of the bounded context to extract
     * @param targetBranch    the base branch in the target repo (e.g. "main")
     * @param sourceType      JIRA, FREE_TEXT, CHAT, or URL
     * @param sourceRef       JIRA key, conversation ID, or null
     * @return a DRAFT ExecutionPlan with markdown content, or null on failure
     */
    public ExecutionPlan generateRewritePlan(String specText,
                                             String sourceRepoUrl, String targetRepoUrl,
                                             String sourceLanguage, String targetLanguage,
                                             String rewriteMode, String scopeHint,
                                             String targetBranch, String sourceType, String sourceRef) {
        if (specText == null || specText.isBlank()) {
            LOG.warn("PlannerService: empty spec text, cannot generate rewrite plan");
            return null;
        }

        String safeSourceRepoUrl = UrlUtils.stripCredentials(sourceRepoUrl);
        String safeTargetRepoUrl = UrlUtils.stripCredentials(targetRepoUrl);
        String effectiveMode = (rewriteMode != null && !rewriteMode.isBlank()) ? rewriteMode : "full_rewrite";
        String effectiveScopeHint = (scopeHint != null) ? scopeHint : "";
        String effectiveTargetBranch = (targetBranch != null && !targetBranch.isBlank()) ? targetBranch : "main";

        String prompt = promptTemplates.resolve("rewrite-planner", Map.of(
                "SOURCE_REPO_URL", safeSourceRepoUrl != null ? safeSourceRepoUrl : "(unspecified)",
                "TARGET_REPO_URL", safeTargetRepoUrl != null ? safeTargetRepoUrl : "(unspecified)",
                "SOURCE_LANGUAGE", sourceLanguage != null ? sourceLanguage : "(unspecified)",
                "TARGET_LANGUAGE", targetLanguage != null ? targetLanguage : "(unspecified)",
                "REWRITE_MODE", effectiveMode,
                "SCOPE_HINT", effectiveScopeHint,
                "TARGET_BRANCH", effectiveTargetBranch,
                "SPEC", specText
        ));

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
                safeTargetRepoUrl,
                safeSourceRepoUrl,
                effectiveTargetBranch,
                title,
                new PlanData(List.of()),
                now,
                now,
                null,
                null,
                null,
                null,
                null,
                markdownContent,
                null,
                false,
                null // createdBy set by PlanResource after generation
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
        String modelName = settings.get("anthropic.model", "claude-sonnet-4-20250514");
        long maxTokens = Long.parseLong(settings.get("planner.max-tokens", "8192"));
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
        if (sourceRef != null && !sourceRef.isBlank() && !looksLikeId(sourceRef)) {
            return sourceRef;
        }
        String firstLine = specText.lines().findFirst().orElse("").strip();
        return firstLine.length() > 80 ? firstLine.substring(0, 77) + "..." : firstLine;
    }

    /**
     * Returns true when the ref looks like a raw UUID or a prefixed UUID
     * (e.g. "chat-4d485372-c0fb-412b-aeb1-887b22a495d1") that would make
     * a confusing plan title.
     */
    private static boolean looksLikeId(String ref) {
        // Match bare UUIDs or kebab-prefixed UUIDs like "chat-<uuid>" / "plan-<uuid>"
        return ref.matches("[0-9a-fA-F\\-]{36}")
                || ref.matches("[a-zA-Z0-9]+-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
}
