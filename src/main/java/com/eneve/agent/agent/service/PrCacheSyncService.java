package com.eneve.agent.agent.service;

import com.eneve.agent.RepoSettingsService;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.PrCacheStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.OpenPrEntry;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Populates the open_pull_requests cache on application startup and provides
 * {@link #syncAll()} for on-demand refresh (e.g. admin-triggered sync endpoint).
 *
 * <p>The sync is a no-op when {@code pr.cache.sync.enabled=false}.
 */
@ApplicationScoped
public class PrCacheSyncService {

    private static final Logger LOG = Logger.getLogger(PrCacheSyncService.class);

    @ConfigProperty(name = "pr.cache.sync.enabled", defaultValue = "true")
    boolean syncEnabled;

    @Inject
    RepoSettingsService repoSettingsService;

    @Inject
    GitPlatformRegistry gitPlatformRegistry;

    @Inject
    PrCacheStore prCacheStore;

    @Inject
    JobStore jobStore;

    @Inject
    JobQueue jobQueue;

    void onStartup(@Observes StartupEvent event) {
        if (!syncEnabled) {
            LOG.info("PR cache sync skipped — pr.cache.sync.enabled=false");
            return;
        }
        Thread t = new Thread(() -> {
            syncAll();
            reconcileActiveJobs();
        }, "pr-cache-sync-startup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Fetches open PRs from the SCM for every non-archived repository and
     * replaces the cached entries for each repo.
     *
     * @return total number of PRs written to the cache
     */
    public int syncAll() {
        List<RepoSettings> repos = repoSettingsService.listAll().stream()
                .filter(r -> !Boolean.TRUE.equals(r.archived()))
                .toList();

        if (repos.isEmpty()) {
            LOG.info("PR cache sync skipped — no active repos configured");
            return 0;
        }

        int total = 0;
        GitPlatformService platform = gitPlatformRegistry.defaultPlatform();

        for (RepoSettings repo : repos) {
            try {
                List<OpenPrEntry> prs = platform.listOpenPullRequests(
                        repo.workspace(), "", repo.repoSlug());
                prCacheStore.replaceForRepo(repo.workspace(), repo.repoSlug(), prs);
                total += prs.size();
                LOG.infof("PR cache sync: %d open PRs cached for %s/%s",
                        prs.size(), repo.workspace(), repo.repoSlug());
            } catch (Exception e) {
                LOG.warnf("PR cache sync failed for %s/%s (non-fatal): %s",
                        repo.workspace(), repo.repoSlug(), e.getMessage());
            }
        }

        LOG.infof("PR cache sync complete — %d PRs across %d repos", total, repos.size());
        return total;
    }

    /**
     * Cancels any active REVIEW jobs whose PR is already marked as MERGED or DECLINED
     * in the local cache. This handles the case where a PR was closed while the
     * application was offline.
     *
     * <p>Only the local cache is consulted — no SCM API calls are made.
     *
     * @return number of jobs cancelled
     */
    public int reconcileActiveJobs() {
        List<JobRecord> activeJobs = jobStore.findActiveReviewJobs();
        if (activeJobs.isEmpty()) {
            LOG.debug("PR job reconciliation: no active REVIEW jobs found");
            return 0;
        }

        int cancelled = 0;
        for (JobRecord job : activeJobs) {
            String prId = job.getPrId();
            String workspace = job.getWorkspace();
            String repoSlug = job.getRepoSlug();

            if (prId == null || workspace == null || repoSlug == null) {
                continue;
            }

            try {
                OpenPrEntry cached = prCacheStore.findByPrId(workspace, repoSlug, prId);
                if (cached == null) {
                    continue;
                }
                String status = cached.status();
                if ("MERGED".equalsIgnoreCase(status) || "DECLINED".equalsIgnoreCase(status)) {
                    // cancelJob handles PENDING/QUEUED; RUNNING jobs are left to finish naturally
                    if (jobQueue.cancelJob(job.getJobId())) {
                        cancelled++;
                        LOG.infof("PR job reconciliation: cancelled job %s — PR %s/%s#%s is %s",
                                job.getJobId(), workspace, repoSlug, prId, status);
                    }
                }
            } catch (Exception e) {
                LOG.warnf("PR job reconciliation: error checking job %s (PR %s/%s#%s): %s",
                        job.getJobId(), workspace, repoSlug, prId, e.getMessage());
            }
        }

        LOG.infof("PR job reconciliation complete — %d of %d active jobs cancelled",
                cancelled, activeJobs.size());
        return cancelled;
    }
}
