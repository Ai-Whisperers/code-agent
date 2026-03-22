package com.eneve.agent.agent.service;

import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.scm.GitPlatformService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;



/**
 * Syncs repositories into the {@code repo_settings} table on startup.
 * New repos get default settings (review enabled, no custom prompt).
 * Existing settings are left untouched.
 */
@ApplicationScoped
public class RepoSyncService {

    private static final Logger LOG = Logger.getLogger(RepoSyncService.class);

    @Inject
    GitPlatformService gitPlatformService;

    @Inject
    RepoSettingsStore settingsStore;

    @ConfigProperty(name = "bitbucket.workspace", defaultValue = "")
    String workspace;

    void onStartup(@Observes StartupEvent event) {
        if (workspace.isBlank()) {
            LOG.warn("bitbucket.workspace is not configured — skipping repo sync");
            return;
        }
        Thread t = new Thread(this::syncRepos, "repo-sync-startup");
        t.setDaemon(true);
        t.start();
    }

    private void syncRepos() {
        try {
            List<String> repoSlugs = gitPlatformService.listRepositories(workspace);
            int newCount = 0;
            for (String slug : repoSlugs) {
                if (settingsStore.insertIfAbsent(workspace, slug)) {
                    newCount++;
                }
            }
            int existing = repoSlugs.size() - newCount;
            LOG.infof("Repo sync complete for workspace '%s': %d repos (%d new, %d already configured)",
                    workspace, repoSlugs.size(), newCount, existing);
        } catch (Exception e) {
            LOG.warnf("Repo sync failed for workspace '%s' (non-fatal): %s", workspace, e.getMessage());
        }
    }
}
