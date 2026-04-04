package com.eneve.agent.webhooks;

import com.eneve.agent.agent.HookEvaluator;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.model.WebhookAuditEntry;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.PrCacheStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.agent.store.WebhookAuditStore;
import com.eneve.agent.loganalysis.LogAnalysisFindingsStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.OpenPrEntry;
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
    @Inject protected PrCacheStore prCacheStore;
    @Inject protected RepoSettingsStore repoSettingsStore;
    @Inject protected HookEvaluator hookEvaluator;
    @Inject protected WebhookAuditStore webhookAuditStore;
    @Inject protected SettingsService settingsService;
    @Inject protected ObjectMapper objectMapper;
    @Inject protected LogAnalysisFindingsStore logAnalysisFindingsStore;

    // ─── Shared helpers ───────────────────────────────────────────────────

    protected Response submitReviewJob(String repoUrl, String prId, String targetBranch,
                                       String jiraKey, String headCommitSha,
                                       String workspace, String repoSlug, String prAuthor) {
        if (jobStore.hasActiveReviewJobForPr(prId, workspace, repoSlug)) {
            LOG.infof("%s webhook skipped duplicate review for PR/MR %s (%s/%s) — active review job already exists",
                    getClass().getSimpleName(), prId, workspace, repoSlug);
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

    /**
     * Common handler for a merged (or declined) PR event across all platforms.
     * <ol>
     *   <li>Upserts the PR cache row with the given {@code cacheStatus} (typically {@code "MERGED"}).</li>
     *   <li>Cancels any active REVIEW jobs for the PR so they do not run against a closed PR.</li>
     *   <li>Writes a webhook audit entry.</li>
     * </ol>
     *
     * @param cacheStatus the status string to store in the cache, e.g. {@code "MERGED"} or {@code "DECLINED"}
     * @param hookJobIds  job IDs already triggered by hook evaluation (included in the response)
     * @param hookNames   hook names already evaluated (included in the audit)
     */
    protected Response handleMergedPr(
            String platform, String workspace, String repoSlug,
            String prId, String prUrl, String prTitle,
            String sourceBranch, String targetBranch, String prAuthor,
            String createdOn, String updatedOn, String rawPayload,
            String cacheStatus, List<String> hookJobIds, List<String> hookNames) {

        // 1. Update PR cache status
        try {
            prCacheStore.upsert(new OpenPrEntry(
                    workspace, repoSlug, prId, prUrl, prTitle,
                    sourceBranch, targetBranch, prAuthor,
                    createdOn, updatedOn, null, cacheStatus, false));
        } catch (Exception e) {
            LOG.warnf("handleMergedPr: failed to upsert PR cache for %s/%s#%s: %s",
                    workspace, repoSlug, prId, e.getMessage());
        }

        // 2. If the PR title contains a Jira key linked to a log-analysis finding, move it to MONITORING
        if ("MERGED".equals(cacheStatus)) {
            String jiraKey = extractJiraKey(prTitle);
            if (jiraKey != null) {
                logAnalysisFindingsStore.findByJiraKey(jiraKey).ifPresent(finding -> {
                    boolean updated = logAnalysisFindingsStore.setMonitoring(finding.id());
                    if (updated) {
                        LOG.infof("handleMergedPr: finding %d (%s) moved to MONITORING after merge of %s/%s#%s",
                                finding.id(), jiraKey, workspace, repoSlug, prId);
                    }
                });
            }
        }

        // 3. Cancel any active review jobs for this PR (scoped to this repo to avoid cross-repo collisions)
        int cancelled = 0;
        try {
            List<JobRecord> activeJobs = jobStore.findByPrId(prId, workspace, repoSlug);
            for (JobRecord job : activeJobs) {
                JobStatus s = job.getStatus();
                if (s == JobStatus.PENDING || s == JobStatus.QUEUED) {
                    if (jobQueue.cancelJob(job.getJobId())) {
                        cancelled++;
                        LOG.infof("handleMergedPr: cancelled job %s for %s PR %s/%s#%s",
                                job.getJobId(), cacheStatus.toLowerCase(), workspace, repoSlug, prId);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("handleMergedPr: failed to cancel jobs for PR %s/%s#%s: %s",
                    workspace, repoSlug, prId, e.getMessage());
        }

        // 4. Audit
        audit(platform, platform + ".pr_" + cacheStatus.toLowerCase(),
                workspace, repoSlug, prId, prAuthor,
                cacheStatus.toLowerCase(), hookNames, rawPayload);

        return Response.ok(Map.of(
                "action", cacheStatus.toLowerCase(),
                "hooksTriggered", hookJobIds.size(),
                "jobIds", hookJobIds,
                "jobsCancelled", cancelled
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
