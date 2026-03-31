package com.eneve.agent.agent.handlers;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.store.JiraIssueReviewStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.ScopeItemOverrideStore;
import com.eneve.agent.agent.store.ScopeItemStore;
import com.eneve.agent.agent.service.JiraReviewContextBuilder;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.JiraReviewRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Shared skeleton for scope-item review handlers (Epic, Feature, UserStory).
 *
 * <p>Concrete subclasses provide the job type, prompt template key, item type label, and
 * context-building logic; all other behaviour — Claude call, JSON parse, store upsert,
 * override guard, success/failure bookkeeping — is handled here.
 */
public abstract class AbstractScopeItemReviewHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(AbstractScopeItemReviewHandler.class);
    @Inject ObjectMapper mapper;

    @Inject AnthropicClient client;
    @Inject PromptTemplateService promptTemplates;
    @Inject JiraReviewContextBuilder contextBuilder;
    @Inject JiraIssueReviewStore reviewStore;
    @Inject ScopeItemOverrideStore overrideStore;
    @Inject ScopeItemStore scopeItemStore;
    @Inject JobStore jobStore;
    @Inject JiraService jiraService;
    @Inject SettingsService settings;

    // ─── Abstract contract ────────────────────────────────────────────────

    /** Jira issue-type label stored in {@code jira_issue_reviews.item_type}, e.g. {@code "FEATURE"}. */
    protected abstract String itemTypeLabel();

    /**
     * Builds the Jira context string passed to the prompt template.
     *
     * @param req          the review request
     * @param scopeItems   the injected {@link ScopeItemStore} (may be ignored for leaf types)
     */
    protected abstract String buildContext(JiraReviewRequest req, ScopeItemStore scopeItems);

    /** Prompt template key, e.g. {@code "review-feature"}. */
    protected abstract String promptTemplateKey();

    // ─── Shared handle skeleton ───────────────────────────────────────────

    @Override
    public final void handle(JobRecord job) {
        JiraReviewRequest req = job.getJiraReviewRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        if (req.scopeId() != null && overrideStore.isOverridden(req.scopeId(), req.issueKey())) {
            LOG.infof("%s %s: item is overridden, skipping Claude call",
                    getClass().getSimpleName(), req.issueKey());
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary("Skipped: item has ACCEPTED or REMOVED override");
            jobStore.archive(job);
            return;
        }

        String issueSummary = fetchSummary(req.issueKey());
        String context = buildContext(req, scopeItemStore);
        String prompt  = promptTemplates.resolve(promptTemplateKey(), Map.of("jira_context", context));

        String responseText = callClaude(prompt, job.getJobId());
        if (responseText == null) {
            fail(job, "Claude call returned no content for " + req.issueKey());
            return;
        }

        String cleaned = extractJson(responseText);
        JsonNode root;
        try {
            root = mapper.readTree(cleaned);
        } catch (Exception e) {
            fail(job, "Malformed JSON from Claude for " + req.issueKey() + ": " + e.getMessage());
            return;
        }

        int readinessScore     = clamp(root.path("readiness_score").asInt(0));
        String readinessLabel  = root.path("readiness_label").asText("poor");
        int complexityScore    = clamp(root.path("complexity_score").asInt(0));
        String improvSummary   = root.path("improvement_summary").asText("");

        String rawStatus  = jiraService.fetchIssueStatus(req.issueKey());
        String jiraStatus = JiraStatusMapper.map(rawStatus, settings);

        reviewStore.upsert(
                req.scopeId(), req.issueKey(), itemTypeLabel(), issueSummary,
                req.parentKey(), jiraStatus,
                readinessScore, readinessLabel, complexityScore, improvSummary,
                cleaned, job.getJobId()
        );

        job.setStatus(JobStatus.SUCCESS);
        job.setSummary(itemTypeLabel().charAt(0) + itemTypeLabel().substring(1).toLowerCase()
                + " " + req.issueKey() + " reviewed: readiness=" + readinessScore
                + " complexity=" + complexityScore);
        jobStore.archive(job);
        LOG.infof("%s %s: readiness=%d label=%s complexity=%d",
                getClass().getSimpleName(), req.issueKey(), readinessScore, readinessLabel, complexityScore);
    }

    // ─── Shared helpers ───────────────────────────────────────────────────

    private String callClaude(String prompt, String jobId) {
        String modelName = settings.get("roadmap.review.model", "");
        if (modelName.isBlank()) modelName = settings.get("anthropic.model", "claude-3-5-sonnet-20241022");
        int maxTokens = Integer.parseInt(settings.get("roadmap.review.max-tokens", "4096"));

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(maxTokens)
                .messages(List.of(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(prompt)
                        .build()))
                .build();
        try {
            Message response = client.messages().create(params);
            for (ContentBlock block : response.content()) {
                if (block.isText()) return block.asText().text().trim();
            }
            return null;
        } catch (Exception e) {
            LOG.errorf("%s: Claude call failed for job %s: %s",
                    getClass().getSimpleName(), jobId, e.getMessage());
            return null;
        }
    }

    private String fetchSummary(String issueKey) {
        var detail = jiraService.fetchIssueDetail(issueKey);
        return detail != null ? detail.summary() : null;
    }

    private static String extractJson(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            int nl  = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).strip();
        }
        return s;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private void fail(JobRecord job, String message) {
        LOG.errorf("%s: %s", getClass().getSimpleName(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
    }
}
