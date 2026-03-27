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
import com.eneve.agent.model.JobType;
import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Handles {@link JobType#REVIEW_EPIC} jobs.
 * Fetches enriched Jira context, calls Claude for a structured readiness review,
 * and upserts the result into {@code jira_issue_reviews}.
 */
@ApplicationScoped
public class ReviewEpicHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(ReviewEpicHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject AnthropicClient client;
    @Inject PromptTemplateService promptTemplates;
    @Inject JiraReviewContextBuilder contextBuilder;
    @Inject JiraIssueReviewStore reviewStore;
    @Inject ScopeItemOverrideStore overrideStore;
    @Inject ScopeItemStore scopeItemStore;
    @Inject JobStore jobStore;
    @Inject JiraService jiraService;
    @Inject SettingsService settings;

    @Override
    public JobType jobType() { return JobType.REVIEW_EPIC; }

    @Override
    public void handle(JobRecord job) {
        JiraReviewRequest req = job.getJiraReviewRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        // Override guard — only applies to roadmap-scoped jobs
        if (req.roadmapId() != null && overrideStore.isOverridden(req.roadmapId(), req.issueKey())) {
            LOG.infof("ReviewEpic %s: item is overridden, skipping Claude call", req.issueKey());
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary("Skipped: item has ACCEPTED or REMOVED override");
            jobStore.archive(job);
            return;
        }

        String issueSummary = fetchSummary(req.issueKey());
        int featureCount = req.roadmapId() != null
                ? scopeItemStore.countChildrenByParent(req.roadmapId(), req.issueKey())
                : -1;
        String context = contextBuilder.buildEpicContext(req.issueKey(), featureCount);
        String prompt = promptTemplates.resolve("review-epic", Map.of("jira_context", context));

        String responseText = callClaude(prompt, job.getJobId());
        if (responseText == null) {
            fail(job, "Claude call returned no content for " + req.issueKey());
            return;
        }

        String cleaned = extractJson(responseText);
        JsonNode root;
        try {
            root = MAPPER.readTree(cleaned);
        } catch (Exception e) {
            fail(job, "Malformed JSON from Claude for " + req.issueKey() + ": " + e.getMessage());
            return;
        }

        int readinessScore = clamp(root.path("readiness_score").asInt(0));
        String readinessLabel = root.path("readiness_label").asText("poor");
        int complexityScore = clamp(root.path("complexity_score").asInt(0));
        String improvementSummary = root.path("improvement_summary").asText("");

        String rawStatus = jiraService.fetchIssueStatus(req.issueKey());
        String jiraStatus = JiraStatusMapper.map(rawStatus, settings);

        reviewStore.upsert(
                req.roadmapId(), req.issueKey(), "EPIC", issueSummary,
                req.parentKey(), jiraStatus,
                readinessScore, readinessLabel, complexityScore, improvementSummary,
                cleaned, job.getJobId()
        );

        job.setStatus(JobStatus.SUCCESS);
        job.setSummary("Epic " + req.issueKey() + " reviewed: readiness=" + readinessScore
                + " complexity=" + complexityScore);
        jobStore.archive(job);
        LOG.infof("ReviewEpic %s: readiness=%d label=%s complexity=%d",
                req.issueKey(), readinessScore, readinessLabel, complexityScore);
    }

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
            LOG.errorf("ReviewEpicHandler: Claude call failed for job %s: %s", jobId, e.getMessage());
            return null;
        }
    }

    private static String extractJson(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).strip();
        }
        return s;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String fetchSummary(String issueKey) {
        var detail = jiraService.fetchIssueDetail(issueKey);
        return detail != null ? detail.summary() : null;
    }

    private void fail(JobRecord job, String message) {
        LOG.errorf("ReviewEpicHandler: %s", message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
    }
}
