package com.eneve.agent.agent;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.audit.AuditStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.scytale.ScytaleService;
import com.eneve.agent.Soc2Policy;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Thin dispatcher: resolves the correct {@link JobHandler} for a job type and delegates to it.
 * The approve/reject lifecycle actions remain here because they are not job-type handlers —
 * they operate on already-completed jobs awaiting a human decision.
 */
@ApplicationScoped
public class AgentRunner {

    private static final Logger LOG = Logger.getLogger(AgentRunner.class);

    @Inject Instance<JobHandler> handlerInstances;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject JiraService jiraService;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;
    @Inject ScytaleService scytaleService;
    @Inject SettingsService settings;
    @Inject AuditStore auditStore;
    @Inject Soc2Policy soc2Policy;

    private volatile Map<JobType, JobHandler> handlers;

    private Map<JobType, JobHandler> handlers() {
        if (handlers == null) {
            synchronized (this) {
                if (handlers == null) {
                    handlers = handlerInstances.stream()
                            .collect(Collectors.toMap(JobHandler::jobType, h -> h));
                }
            }
        }
        return handlers;
    }

    public void dispatch(JobRecord job) {
        JobHandler handler = handlers().get(job.getJobType());
        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for job type: " + job.getJobType());
        }
        MDC.put("jobId", job.getJobId());
        MDC.put("jobType", job.getJobType().name());
        if (job.getRepoSlug() != null) MDC.put("repoSlug", job.getRepoSlug());
        if (job.getWorkspace() != null) MDC.put("workspace", job.getWorkspace());
        try {
            handler.handle(job);
        } finally {
            MDC.remove("jobId");
            MDC.remove("jobType");
            MDC.remove("repoSlug");
            MDC.remove("workspace");
        }
    }

    // ─── Approve / Reject ───────────────────────────────────────────────

    public void approve(JobRecord job) {
        String repoUrl = lifecycle.resolveRepoUrl(job);
        String jiraKey = lifecycle.resolveJiraKey(job);
        RepoCoordinates coords = RepoCoordinates.parse(repoUrl);
        GitPlatformService platformService = platformRegistry.resolve(repoUrl);

        try {
            platformService.mergePullRequest(
                    coords.organization(), coords.project(), coords.repository(), job.getPrId());
            job.setStatus(JobStatus.SUCCESS);
            jobStore.archive(job);
            lifecycle.auditLog("JOBS", "MERGE_COMPLETED", "job", job.getJobId(),
                    Map.of("prId", job.getPrId() != null ? job.getPrId() : "unknown",
                           "targetBranch", job.getPrUrl() != null ? job.getPrUrl() : "unknown"));
            if (jiraKey != null && !jiraKey.isBlank()) {
                lifecycle.safeJira(() -> jiraService.commentMerged(jiraKey));
                lifecycle.safeJira(() -> jiraService.transitionToDone(jiraKey));
            }

            // Auto-upload SOC II evidence to Scytale when merging a Bug-fix to the production branch
            tryScytaleAutoUpload(job);

            LOG.infof("Job %s approved and merged", job.getJobId());
        } catch (Exception e) {
            LOG.errorf("Failed to merge PR for job %s: %s", job.getJobId(), e.getMessage());
            throw new RuntimeException("Merge failed: " + e.getMessage(), e);
        }
    }

    // ── Scytale auto-upload ─────────────────────────────────────────────

    private void tryScytaleAutoUpload(JobRecord job) {
        try {
            String apiKey      = settings.get("scytale.api.key",        "");
            if (apiKey.isBlank()) return;
            if (!JobStore.isSoc2Applicable(job, soc2Policy.bugIssueTypes())) return;

            // Only auto-upload when merging to the production branch
            String target = null;
            if (job.getRequest() != null)      target = job.getRequest().targetBranchOrDefault();
            else if (job.getHookRequest() != null) target = job.getHookRequest().targetBranch();
            if (target == null || !target.equalsIgnoreCase(soc2Policy.productionBranch())) return;

            List<com.eneve.agent.audit.AuditEntry> rawEntries = auditStore.findByResourceId(job.getJobId(), 200);
            List<Map<String, Object>> checksPayload = List.of(
                    Map.of("name", "Bot code review completed",
                            "passed", rawEntries.stream().anyMatch(e -> "REVIEW_COMPLETED".equals(e.action()))),
                    Map.of("name", "Human approval obtained",
                            "passed", rawEntries.stream().anyMatch(e -> "JOB_APPROVED".equals(e.action()))),
                    Map.of("name", "Merged to production",
                            "passed", true)
            );
            List<Map<String, Object>> auditPayload = rawEntries.stream()
                    .map(e -> Map.<String, Object>of(
                            "timestamp", e.occurredAt().toString(),
                            "actor",     e.actor(),
                            "action",    e.action(),
                            "detail",    e.detail() != null ? e.detail() : ""))
                    .toList();

            ScytaleService.ScytaleUploadResult result = scytaleService.upload(job, checksPayload, auditPayload);
            if (result.success()) {
                job.setScytaleEvidenceRef(result.ref());
                job.setScytaleUploadedAt(Instant.now());
                jobStore.update(job);
                lifecycle.auditLog("SOC2", "SOC2_EVIDENCE_UPLOADED", "job", job.getJobId(),
                        Map.of("scytaleRef", result.ref(), "trigger", "auto-upload-on-merge"));
            } else {
                lifecycle.auditLog("SOC2", "SOC2_EVIDENCE_UPLOAD_FAILED", "job", job.getJobId(),
                        Map.of("error", result.errorMessage() != null ? result.errorMessage() : "unknown",
                               "trigger", "auto-upload-on-merge"));
                LOG.warnf("Scytale auto-upload failed for job %s: %s", job.getJobId(), result.errorMessage());
            }
        } catch (Exception e) {
            LOG.warnf("Scytale auto-upload error for job %s (non-fatal): %s", job.getJobId(), e.getMessage());
        }
    }

    public void reject(JobRecord job, String reason) {
        String repoUrl = lifecycle.resolveRepoUrl(job);
        String jiraKey = lifecycle.resolveJiraKey(job);
        RepoCoordinates coords = RepoCoordinates.parse(repoUrl);
        GitPlatformService platformService = platformRegistry.resolve(repoUrl);

        try {
            platformService.declinePullRequest(
                    coords.organization(), coords.project(), coords.repository(), job.getPrId());
        } catch (Exception e) {
            LOG.warnf("Failed to decline PR for job %s: %s", job.getJobId(), e.getMessage());
        }

        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage("Rejected: " + (reason != null ? reason : "No reason provided"));
        jobStore.archive(job);
        if (jiraKey != null && !jiraKey.isBlank()) {
            lifecycle.safeJira(() -> jiraService.commentRejected(jiraKey, reason));
            lifecycle.safeJira(() -> jiraService.transitionToRejected(jiraKey));
        }
        LOG.infof("Job %s rejected", job.getJobId());
    }
}
