package com.eneve.agent.agent;

import com.eneve.agent.audit.AuditEntry;
import com.eneve.agent.audit.AuditStore;
import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import com.eneve.agent.agent.store.JobStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.FixPrRequest;
import com.eneve.agent.model.GenerateDocsRequest;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.HookJobRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.MetricsJobRequest;
import com.eneve.agent.model.ReplyCommentRequest;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.model.RunResult;
import com.eneve.agent.notifications.N8nWebhookNotifier;
import com.eneve.agent.notifications.TeamsNotifier;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;

/**
 * Shared lifecycle utilities: failure handlers, result builders, JIRA/comment helpers,
 * and notification dispatch. Injected by every job handler.
 */
@ApplicationScoped
public class JobLifecycleHelper {

    private static final Logger LOG = Logger.getLogger(JobLifecycleHelper.class);

    @Inject JobStore jobStore;
    @Inject JiraService jiraService;
    @Inject TeamsNotifier teamsNotifier;
    @Inject N8nWebhookNotifier n8nNotifier;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject com.eneve.agent.settings.SettingsService settings;
    @Inject AuditStore auditStore;

    /** Fires an audit event asynchronously from the application-scoped lifecycle helper. */
    public void auditLog(String category, String action, String resourceType, String resourceId, java.util.Map<String, Object> detail) {
        String detailJson = null;
        if (detail != null && !detail.isEmpty()) {
            try {
                detailJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail);
            } catch (Exception ignored) {}
        }
        final String d = detailJson;
        Thread.ofVirtual().name("audit-lifecycle-" + action).start(() -> {
            try {
                auditStore.save(new AuditEntry(null, "system", category, action,
                        resourceType, resourceId, d, java.time.Instant.now()));
            } catch (Exception e) {
                LOG.warnf("Async lifecycle audit write failed [%s/%s]: %s", category, action, e.getMessage());
            }
        });
    }

    // ─── Failure handlers ───────────────────────────────────────────────

    /**
     * Returns true if the job has already been marked CANCELLED (e.g. by a concurrent cancel
     * request). All fail-handlers check this before overriding status to FAILED.
     */
    private boolean isAlreadyCancelled(JobRecord job) {
        if (job.getStatus() == JobStatus.CANCELLED) {
            LOG.infof("Job %s already cancelled — skipping FAILED override", job.getJobId());
            return true;
        }
        return false;
    }

    public void failFix(JobRecord job, String message) {
        if (isAlreadyCancelled(job)) return;
        LOG.errorf("Job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
        auditLog("JOBS", "JOB_FAILED", "job", job.getJobId(), java.util.Map.of("errorMessage", message));

        RunFixRequest request = job.getRequest();
        safeJira(() -> jiraService.commentFailure(request.jiraKey(), "Automated fix", message));

        RunResult result = buildResult(job, false);
        teamsNotifier.sendNotification(result);
        n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);
    }

    public void failReview(JobRecord job, String message) {
        if (isAlreadyCancelled(job)) return;
        LOG.errorf("Review job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
        auditLog("JOBS", "REVIEW_FAILED", "job", job.getJobId(), java.util.Map.of("errorMessage", message));

        ReviewPrRequest request = job.getReviewRequest();
        if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
            safeJira(() -> jiraService.commentFailure(request.jiraKey(), "Code review", message));
        }

        RunResult result = buildReviewResult(job, false);
        teamsNotifier.sendNotification(result);
        n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);
    }

    public void failFixPr(JobRecord job, String message) {
        if (isAlreadyCancelled(job)) return;
        LOG.errorf("Fix-PR job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        FixPrRequest request = job.getFixPrRequest();
        if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
            safeJira(() -> jiraService.commentFailure(request.jiraKey(), "Fix PR", message));
        }

        RunResult result = buildFixPrResult(job, false);
        teamsNotifier.sendNotification(result);
        n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);
    }

    public void failGenerateTests(JobRecord job, String message) {
        if (isAlreadyCancelled(job)) return;
        LOG.errorf("GenerateTests job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        GenerateTestsRequest request = job.getGenerateTestsRequest();
        if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
            safeJira(() -> jiraService.commentFailure(request.jiraKey(), "Generate tests", message));
        }

        RunResult result = buildGenerateTestsResult(job, false);
        teamsNotifier.sendNotification(result);
        n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);
    }

    public void failGenerateDocs(JobRecord job, String message) {
        if (isAlreadyCancelled(job)) return;
        LOG.errorf("GenerateDocs job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        RunResult result = buildGenerateDocsResult(job, false);
        teamsNotifier.sendNotification(result);

        GenerateDocsRequest request = job.getGenerateDocsRequest();
        if (request != null) {
            n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);
        }
    }

    public void failSyncConfluence(JobRecord job, String message) {
        if (isAlreadyCancelled(job)) return;
        LOG.errorf("SyncConfluence job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
    }

    public void failHook(JobRecord job, String message) {
        if (isAlreadyCancelled(job)) return;
        LOG.errorf("Hook job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
        teamsNotifier.sendNotification(buildHookResult(job, false));
    }

    public void failMetrics(JobRecord job, String message) {
        if (isAlreadyCancelled(job)) return;
        LOG.errorf("Metrics job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
        teamsNotifier.sendNotification(buildMetricsResult(job, false));
    }

    public void failQualityReport(JobRecord job, String message) {
        if (isAlreadyCancelled(job)) return;
        LOG.errorf("QualityReport job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
    }

    public void failReply(JobRecord job, String message) {
        if (isAlreadyCancelled(job)) return;
        LOG.errorf("Reply job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
    }

    public void failFixComment(JobRecord job, ReplyCommentRequest request, String message) {
        if (isAlreadyCancelled(job)) return;
        LOG.errorf("FixComment job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        teamsNotifier.sendNotification(buildFixCommentResult(job, false));

        try {
            RepoCoordinates c = RepoCoordinates.parse(request.repoUrl());
            GitPlatformService platformService = platformRegistry.resolve(request.repoUrl());
            platformService.replyToComment(
                    c.organization(), c.project(), c.repository(), request.prId(),
                    request.parentCommentId(),
                    "Failed to apply fix: " + message);
        } catch (Exception e) {
            LOG.warnf("Failed to post fix failure reply (non-fatal): %s", e.getMessage());
        }
    }

    // ─── Result builders ────────────────────────────────────────────────

    public RunResult buildResult(JobRecord job, boolean success) {
        RunFixRequest req = job.getRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                req.jiraKey(), req.repoUrl(), req.branchName(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    public RunResult buildReviewResult(JobRecord job, boolean success) {
        ReviewPrRequest req = job.getReviewRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                req.jiraKey() != null ? req.jiraKey() : "",
                req.repoUrl(),
                "PR-" + req.prId(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                0, 0);
    }

    public RunResult buildFixPrResult(JobRecord job, boolean success) {
        FixPrRequest req = job.getFixPrRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                req.jiraKey() != null ? req.jiraKey() : "",
                req.repoUrl(),
                "fix-pr-" + req.prId(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    public RunResult buildGenerateTestsResult(JobRecord job, boolean success) {
        GenerateTestsRequest req = job.getGenerateTestsRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                req.jiraKey() != null ? req.jiraKey() : "",
                req.repoUrl(),
                req.branchName(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    public RunResult buildGenerateDocsResult(JobRecord job, boolean success) {
        GenerateDocsRequest req = job.getGenerateDocsRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                "",
                req != null ? req.repoUrl() : "",
                req != null ? req.branchName() : "",
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    public RunResult buildHookResult(JobRecord job, boolean success) {
        HookJobRequest req = job.getHookRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                "",
                req.repoUrl(),
                req.branchName(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    public RunResult buildMetricsResult(JobRecord job, boolean success) {
        MetricsJobRequest req = job.getMetricsRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                "",
                req != null ? req.repoUrl() : "",
                req != null ? req.branch() : "",
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                0, 0);
    }

    public RunResult buildFixCommentResult(JobRecord job, boolean success) {
        ReplyCommentRequest req = job.getReplyRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                "",
                req != null ? req.repoUrl() : "",
                req != null ? "PR-" + req.prId() : "",
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    // ─── Utilities ──────────────────────────────────────────────────────

    public void safeJira(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warnf("JIRA operation failed (non-fatal): %s", e.getMessage());
        }
    }

    public void safeComment(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warnf("Failed to post PR comment (non-fatal): %s", e.getMessage());
        }
    }

    public String resolveWebhookUrl(String requestUrl) {
        return (requestUrl != null && !requestUrl.isBlank()) ? requestUrl : settings.get("n8n.webhook.url", "");
    }

    public String resolveRepoUrl(JobRecord job) {
        if (job.getJobType() == JobType.FIX_PR) {
            return job.getFixPrRequest().repoUrl();
        }
        return job.getRequest().repoUrl();
    }

    public String resolveJiraKey(JobRecord job) {
        if (job.getJobType() == JobType.FIX_PR) {
            return job.getFixPrRequest().jiraKey();
        }
        return job.getRequest().jiraKey();
    }

    /** Sends both Teams and n8n notifications for a completed job result. */
    public void notifyResult(RunResult result, String webhookUrl) {
        teamsNotifier.sendNotification(result);
        n8nNotifier.sendResult(resolveWebhookUrl(webhookUrl), result);
    }
}
