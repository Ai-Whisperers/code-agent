package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.HookEvaluator;
import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.JobLifecycleHelper;
import com.eneve.agent.agent.QualityReportCollector;
import com.eneve.agent.agent.model.HookEvalResult;
import com.eneve.agent.agent.model.QualityReport;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.QualityReportStore;
import com.eneve.agent.model.*;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Handles {@link JobType#QUALITY_REPORT} jobs.
 * Clones the repository at the specified branch, runs all quality measurements via
 * {@link QualityReportCollector}, persists the result, and archives the job with a summary.
 */
@ApplicationScoped
public class QualityReportHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(QualityReportHandler.class);

    @Inject QualityReportCollector collector;
    @Inject QualityReportStore reportStore;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject SettingsService settings;
    @Inject HookEvaluator hookEvaluator;

    @Override
    public JobType jobType() {
        return JobType.QUALITY_REPORT;
    }

    @Override
    public void handle(JobRecord job) {
        long timeoutMinutes = Long.parseLong(settings.get("quality-report.job-timeout-minutes", "30"));
        QualityReportJobRequest request = job.getQualityReportRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failQualityReport(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        final GitPlatformService platformService = platformRegistry.resolve(request.repoUrl());

        String workspaceName = request.workspace() != null ? request.workspace() : coords.organization();
        String repoSlug = request.repoSlug() != null ? request.repoSlug() : coords.repository();

        LOG.infof("QualityReport job %s: measuring %s/%s branch=%s",
                job.getJobId(), workspaceName, repoSlug, request.branch());

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            String authUrl = platformService.buildCloneUrl(coords.organization(), coords.project(), coords.repository());
            try {
                workspace.cloneRepoShallow(authUrl, request.branch(), timeoutMinutes);
            } catch (Exception e) {
                lifecycle.failQualityReport(job, "Clone failed: " + e.getMessage());
                return;
            }

            QualityReport report;
            try {
                report = collector.collect(workspace, workspaceName, repoSlug, request.branch());
            } catch (Exception e) {
                lifecycle.failQualityReport(job, "Quality measurement failed: " + e.getMessage());
                return;
            }

            reportStore.save(report);

            String summary = formatSummary(report);
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(summary);
            jobStore.archive(job);

            LOG.infof("QualityReport job %s complete for %s/%s branch=%s: score=%.4f",
                    job.getJobId(), workspaceName, repoSlug, request.branch(), report.score());

            // Evaluate any hooks registered for the quality.report_generated trigger
            try {
                HookEvalResult hookResult = hookEvaluator.evaluateQualityReport(report, request.repoUrl());
                if (!hookResult.hookNames().isEmpty()) {
                    LOG.infof("Quality report hooks triggered for %s/%s (branch=%s): %s",
                            workspaceName, repoSlug, request.branch(), hookResult.hookNames());
                }
            } catch (Exception e) {
                // Hook evaluation failure must not roll back a successful quality report
                LOG.warnf("Quality report hook evaluation failed for %s/%s: %s",
                        workspaceName, repoSlug, e.getMessage());
            }

        } catch (Exception e) {
            lifecycle.failQualityReport(job, "Unexpected error in quality report job: " + e.getMessage());
        }
    }

    private String formatSummary(QualityReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Quality Report — ").append(report.repoSlug())
          .append(" @ ").append(report.branch()).append("\n\n");
        sb.append("**Aggregate Score: %.4f / 1.0**\n\n".formatted(report.score()));

        if (report.coverage() != null) {
            sb.append("### Coverage\n");
            sb.append("| Metric | Rate |\n|--------|------|\n");
            sb.append("| Lines | %.1f%% |\n".formatted(report.coverage().lineRate()));
            sb.append("| Branches | %.1f%% |\n".formatted(report.coverage().branchRate()));
            sb.append("| Methods | %.1f%% |\n\n".formatted(report.coverage().methodRate()));
        }
        if (report.linter() != null) {
            sb.append("### Linter\n");
            sb.append("Total findings: %d (errors: %d, warnings: %d)\n\n"
                    .formatted(report.linter().totalFindings(),
                               report.linter().errorCount(),
                               report.linter().warningCount()));
        }
        if (report.aikido() != null) {
            sb.append("### Security (Aikido)\n");
            sb.append("Open issues: %d (critical: %d, high: %d, medium: %d, low: %d)\n\n"
                    .formatted(report.aikido().totalIssues(),
                               report.aikido().criticalCount(),
                               report.aikido().highCount(),
                               report.aikido().mediumCount(),
                               report.aikido().lowCount()));
        }
        if (report.complexity() != null) {
            sb.append("### Complexity\n");
            sb.append("Avg CC: %.2f, Max CC: %d, Methods above threshold: %d/%d\n\n"
                    .formatted(report.complexity().avgComplexity(),
                               report.complexity().maxComplexity(),
                               report.complexity().methodsAboveThreshold(),
                               report.complexity().totalMethods()));
        }
        if (report.reviewQuality() != null) {
            sb.append("### Review Quality\n");
            sb.append("Resolution rate: %.1f%%, False-positive rate: %.1f%%\n"
                    .formatted(report.reviewQuality().resolutionRate() * 100,
                               report.reviewQuality().fpRate() * 100));
        }
        return sb.toString();
    }
}
