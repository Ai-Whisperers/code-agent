package com.eneve.agent.agent.service;

import com.eneve.agent.RepoSettingsService;
import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.PrCacheStore;
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

    void onStartup(@Observes StartupEvent event) {
        if (!syncEnabled) {
            LOG.info("PR cache sync skipped — pr.cache.sync.enabled=false");
            return;
        }
        Thread t = new Thread(this::syncAll, "pr-cache-sync-startup");
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
}
