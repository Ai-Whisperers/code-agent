package com.eneve.agent.agent;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.scm.GitPlatformService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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
    @Inject GitPlatformService platformService;
    @Inject JiraService jiraService;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;

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
        handler.handle(job);
    }

    // ─── Approve / Reject ───────────────────────────────────────────────

    public void approve(JobRecord job) {
        String repoUrl = lifecycle.resolveRepoUrl(job);
        String jiraKey = lifecycle.resolveJiraKey(job);
        RepoCoordinates coords = RepoCoordinates.parse(repoUrl);

        try {
            platformService.mergePullRequest(
                    coords.organization(), coords.project(), coords.repository(), job.getPrId());
            job.setStatus(JobStatus.SUCCESS);
            jobStore.archive(job);
            if (jiraKey != null && !jiraKey.isBlank()) {
                lifecycle.safeJira(() -> jiraService.commentMerged(jiraKey));
                lifecycle.safeJira(() -> jiraService.transitionToDone(jiraKey));
            }
            LOG.infof("Job %s approved and merged", job.getJobId());
        } catch (Exception e) {
            LOG.errorf("Failed to merge PR for job %s: %s", job.getJobId(), e.getMessage());
            throw new RuntimeException("Merge failed: " + e.getMessage(), e);
        }
    }

    public void reject(JobRecord job, String reason) {
        String repoUrl = lifecycle.resolveRepoUrl(job);
        String jiraKey = lifecycle.resolveJiraKey(job);
        RepoCoordinates coords = RepoCoordinates.parse(repoUrl);

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
