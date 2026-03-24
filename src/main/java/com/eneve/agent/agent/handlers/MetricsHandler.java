package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.CodeMetricsCalculator;
import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.JobLifecycleHelper;
import com.eneve.agent.agent.store.CodeMetricsStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.*;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MetricsHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(MetricsHandler.class);

    @Inject CodeMetricsCalculator codeMetricsCalculator;
    @Inject CodeMetricsStore codeMetricsStore;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;
    @Inject GitPlatformService platformService;

    @ConfigProperty(name = "metrics.job-timeout-minutes", defaultValue = "30")
    long metricsTimeoutMinutes;

    @Override
    public JobType jobType() {
        return JobType.METRICS;
    }

    @Override
    public void handle(JobRecord job) {
        MetricsJobRequest request = job.getMetricsRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failMetrics(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        LOG.infof("Metrics job %s: analysing %s/%s (branch: %s, threshold: %d)",
                job.getJobId(), coords.organization(), coords.repository(),
                request.branch(), request.effectiveThreshold());

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            String authUrl = platformService.buildCloneUrl(coords.organization(), coords.project(), coords.repository());
            try {
                workspace.cloneRepoShallow(authUrl, request.branch(), metricsTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failMetrics(job, "Clone failed: " + e.getMessage());
                return;
            }

            String wsName = request.workspace() != null ? request.workspace() : coords.organization();
            String repoSlug = request.repoSlug() != null ? request.repoSlug() : coords.repository();

            CodeMetricsCalculator.CodeMetricsSnapshot snapshot;
            try {
                snapshot = codeMetricsCalculator.calculate(
                        workspace.getRoot(), wsName, repoSlug, request.branch(),
                        request.effectiveThreshold());
            } catch (Exception e) {
                lifecycle.failMetrics(job, "Metrics calculation failed: " + e.getMessage());
                return;
            }

            codeMetricsStore.save(snapshot, request.planId());

            String summary = snapshot.formatMarkdownComparison(null);
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(summary);
            jobStore.archive(job);

            LOG.infof("Metrics job %s complete: %d methods, %d above threshold (CC>%d), avg=%.2f",
                    job.getJobId(), snapshot.totalMethods(), snapshot.methodsAboveThreshold(),
                    snapshot.threshold(), snapshot.avgComplexity());

        } catch (Exception e) {
            lifecycle.failMetrics(job, "Unexpected error in metrics job: " + e.getMessage());
        }
    }
}
