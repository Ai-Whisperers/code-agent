package com.eneve.agent.webhooks;

import com.eneve.agent.agent.HookEvaluator;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.model.WebhookAuditEntry;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.agent.store.WebhookAuditStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared logic for the four PR webhook resources (GitHub, GitLab, Bitbucket, AzureDevOps).
 *
 * <p>Each concrete subclass handles only its platform-specific payload parsing and
 * HTTP endpoint binding; all shared helpers live here.
 */
public abstract class AbstractPrWebhookHandler {

    private static final Logger LOG = Logger.getLogger(AbstractPrWebhookHandler.class);
    private static final Pattern JIRA_KEY_PATTERN = Pattern.compile("([A-Z][A-Z0-9]+-\\d+)");

    @Inject protected JobQueue jobQueue;
    @Inject protected JobStore jobStore;
    @Inject protected RepoSettingsStore repoSettingsStore;
    @Inject protected HookEvaluator hookEvaluator;
    @Inject protected WebhookAuditStore webhookAuditStore;
    @Inject protected SettingsService settingsService;
    @Inject protected ObjectMapper objectMapper;

    // ─── Shared helpers ───────────────────────────────────────────────────

    protected Response submitReviewJob(String repoUrl, String prId, String targetBranch,
                                       String jiraKey, String headCommitSha,
                                       String workspace, String repoSlug, String prAuthor) {
        if (jobStore.hasActiveReviewJobForPr(prId)) {
            LOG.infof("%s webhook skipped duplicate review for PR/MR %s — active review job already exists",
                    getClass().getSimpleName(), prId);
            return Response.ok(Map.of(
                    "action", "skipped",
                    "reason", "Active review job already exists for this PR",
                    "prId", prId
            )).build();
        }

        String rulesRepoUrl = settingsService.get("rules.repo.url", "");
        ReviewPrRequest request = new ReviewPrRequest(
                repoUrl, prId, targetBranch, jiraKey,
                rulesRepoUrl.isBlank() ? null : rulesRepoUrl,
                null, null, null, headCommitSha, prAuthor);

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        job.setWorkspace(workspace);
        job.setRepoSlug(repoSlug);
        job.setPrAuthor(prAuthor);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429)
                    .entity(Map.of("action", "rejected", "reason", "Job queue is full"))
                    .build();
        }

        LOG.infof("%s webhook triggered review job %s for PR/MR %s",
                getClass().getSimpleName(), jobId, prId);
        return Response.ok(Map.of(
                "action", "review_triggered",
                "jobId", jobId,
                "prId", prId,
                "jiraKey", jiraKey != null ? jiraKey : ""
        )).build();
    }

    protected boolean shouldSkipAuthor(String author) {
        String skipAuthors = settingsService.get("review.webhook.skip-authors", "code-agent");
        if (skipAuthors.isBlank() || author.isBlank()) return false;
        for (String skip : skipAuthors.split(",")) {
            if (skip.trim().equalsIgnoreCase(author)) return true;
        }
        return false;
    }

    protected static String extractJiraKey(String title) {
        if (title == null || title.isBlank()) return null;
        Matcher matcher = JIRA_KEY_PATTERN.matcher(title);
        return matcher.find() ? matcher.group(1) : null;
    }

    protected void audit(String platform, String eventType, String workspace, String repoSlug,
                         String prId, String author, String action,
                         List<String> hooksExecuted, String payload) {
        try {
            webhookAuditStore.save(WebhookAuditEntry.create(
                    platform, eventType, workspace, repoSlug, prId, author, action, hooksExecuted, payload));
        } catch (Exception e) {
            LOG.warnf("Failed to save webhook audit entry (non-fatal): %s", e.getMessage());
        }
    }

    protected static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
