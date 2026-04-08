package com.eneve.agent.agent.scheduler;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.QualityReportJobRequest;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.settings.SettingsService;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Scheduled daily collection of quality reports for all repos with
 * {@code quality_report_enabled = true}.
 *
 * <p>For each enabled repo, one QUALITY_REPORT job is queued per configured branch
 * (default: {@code main} and {@code develop}).
 *
 * <p>Enabled via {@code quality-report.scheduler.enabled=true} (default: {@code false}).
 */
@ApplicationScoped
public class QualityReportScheduler {

    private static final Logger LOG = Logger.getLogger(QualityReportScheduler.class);

    @Inject RepoSettingsStore repoSettingsStore;
    @Inject JobStore jobStore;
    @Inject JobQueue jobQueue;
    @Inject GitPlatformService platformService;
    @Inject SettingsService settingsService;

    @Scheduled(every = "24h", delayed = "15m",
               concurrentExecution = ConcurrentExecution.SKIP)
    void collectQualityReports() {
        if (!"true".equalsIgnoreCase(settingsService.get("quality-report.scheduler.enabled", "false"))) {
            return;
        }

        String branchesConfig = settingsService.get("quality-report.branches", "main,develop");
        List<RepoSettings> repos = repoSettingsStore.listQualityReportEnabled();
        String[] branches = branchesConfig.split(",");

        LOG.infof("QualityReportScheduler: found %d repos, measuring branches: %s",
                repos.size(), branchesConfig);

        int queued = 0;
        int skipped = 0;

        for (RepoSettings repo : repos) {
            String repoUrl = resolveRepoUrl(repo);
            if (repoUrl == null) {
                LOG.warnf("QualityReportScheduler: cannot resolve clone URL for %s/%s — skipping",
                        repo.workspace(), repo.repoSlug());
                skipped++;
                continue;
            }

            for (String rawBranch : branches) {
                String branch = rawBranch.trim();
                if (branch.isBlank()) continue;

                String jobId = UUID.randomUUID().toString();
                QualityReportJobRequest request = new QualityReportJobRequest(
                        repoUrl, branch, repo.workspace(), repo.repoSlug());
                JobRecord job = new JobRecord(jobId, request);
                job.setWorkspace(repo.workspace());
                job.setRepoSlug(repo.repoSlug());
                jobStore.put(job);

                if (!jobQueue.submit(job)) {
                    LOG.warnf("QualityReportScheduler: queue full, skipping %s/%s branch=%s",
                            repo.workspace(), repo.repoSlug(), branch);
                    skipped++;
                } else {
                    LOG.debugf("QualityReportScheduler: queued job %s for %s/%s branch=%s",
                            jobId, repo.workspace(), repo.repoSlug(), branch);
                    queued++;
                }
            }
        }

        LOG.infof("QualityReportScheduler complete: %d jobs queued, %d skipped", queued, skipped);
    }

    /**
     * Resolves an authenticated HTTPS clone URL for a repo setting via the platform service.
     * Returns {@code null} if the platform service cannot construct a URL (e.g. Azure DevOps
     * without a project segment), in which case the repo is skipped.
     */
    private String resolveRepoUrl(RepoSettings repo) {
        try {
            return platformService.buildCloneUrl(repo.workspace(), repo.repoSlug());
        } catch (Exception e) {
            LOG.debugf("QualityReportScheduler: could not build clone URL for %s/%s: %s",
                    repo.workspace(), repo.repoSlug(), e.getMessage());
        }
        return null;
    }
}
