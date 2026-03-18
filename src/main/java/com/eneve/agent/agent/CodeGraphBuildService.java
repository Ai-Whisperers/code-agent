package com.eneve.agent.agent;

import java.util.List;

import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.WorkspaceContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builds code graphs for repositories that don't have one yet.
 * Used by both the scheduled background job and the on-demand REST endpoint.
 */
@ApplicationScoped
public class CodeGraphBuildService {

    private static final Logger LOG = Logger.getLogger(CodeGraphBuildService.class);

    @Inject CodeGraphStore codeGraphStore;
    @Inject CodeGraphIndexer codeGraphIndexer;
    @Inject EmbeddingIndexer embeddingIndexer;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject ArchetypeDetector archetypeDetector;
    @Inject GitPlatformService platformService;

    @ConfigProperty(name = "code-graph.scheduler.default-branch", defaultValue = "main")
    String defaultBranch;

    @ConfigProperty(name = "code-graph.scheduler.clone-timeout-minutes", defaultValue = "10")
    long cloneTimeoutMinutes;

    public record BuildResult(int built, int skipped, int alreadyPresent) {}

    public record DetectResult(int detected, int skipped, int unchanged) {}

    /**
     * Scans all review-enabled repos and builds a code graph for any that lack one.
     */
    public BuildResult buildMissingGraphs() {
        List<RepoSettings> repos = repoSettingsStore.listAll();
        if (repos.isEmpty()) {
            LOG.debug("No repos in repo_settings — nothing to do");
            return new BuildResult(0, 0, 0);
        }

        int built = 0;
        int skipped = 0;
        int alreadyPresent = 0;

        for (RepoSettings repo : repos) {
            if (!repo.reviewEnabled()) {
                continue;
            }
            if (codeGraphStore.hasGraph(repo.workspace(), repo.repoSlug())) {
                alreadyPresent++;
                continue;
            }

            String cloneUrl = platformService.buildCloneUrl(repo.workspace(), repo.repoSlug());
            if (cloneUrl == null) {
                skipped++;
                continue;
            }

            if (tryBuildGraph(repo.workspace(), repo.repoSlug(), cloneUrl)) {
                built++;
            } else {
                skipped++;
            }
        }

        LOG.infof("Build missing graphs complete: %d built, %d skipped, %d already present",
                built, skipped, alreadyPresent);
        return new BuildResult(built, skipped, alreadyPresent);
    }

    /**
     * Lightweight archetype detection for all repos whose archetype is not yet set.
     * Only clones the default branch root — no code-graph indexing or embedding —
     * so it is much faster than a full rebuild.
     */
    public DetectResult detectArchetypesForAll() {
        List<RepoSettings> repos = repoSettingsStore.listAll();
        int detected = 0, skipped = 0, unchanged = 0;
        for (RepoSettings repo : repos) {
            if (repo.archetype() != null && !repo.archetype().isBlank()) {
                unchanged++;
                continue;
            }
            Boolean result = detectArchetypeOnce(repo.workspace(), repo.repoSlug());
            if (result == null) {
                skipped++;
            } else if (result) {
                detected++;
            } else {
                unchanged++;
            }
        }
        LOG.infof("Archetype detection complete: %d detected, %d skipped, %d unchanged", detected, skipped, unchanged);
        return new DetectResult(detected, skipped, unchanged);
    }

    /**
     * Lightweight archetype detection for a single repository.
     * Only clones the default branch root — no code-graph indexing or embedding.
     * Returns {@code true} if an archetype was newly detected, {@code false} if the repo
     * was already known or unrecognised, {@code null} if the clone failed.
     */
    public Boolean detectArchetype(String workspace, String repoSlug) {
        return detectArchetypeOnce(workspace, repoSlug);
    }

    private Boolean detectArchetypeOnce(String workspace, String repoSlug) {
        String cloneUrl = platformService.buildCloneUrl(workspace, repoSlug);
        if (cloneUrl == null) {
            LOG.debugf("No clone URL for %s/%s — skipping archetype detection", workspace, repoSlug);
            return null;
        }

        try (WorkspaceContext ws = WorkspaceContext.create("archetype-" + workspace + "-" + repoSlug)) {
            try {
                ws.cloneRepo(cloneUrl, defaultBranch, cloneTimeoutMinutes);
            } catch (Exception e) {
                if (!"main".equals(defaultBranch)) {
                    LOG.debugf("Archetype clone failed for %s/%s on '%s': %s", workspace, repoSlug, defaultBranch, e.getMessage());
                    return null;
                }
                // Try master as fallback
                try {
                    ws.cloneRepo(cloneUrl, "master", cloneTimeoutMinutes);
                } catch (Exception e2) {
                    LOG.warnf("Archetype clone failed for %s/%s on both branches: %s", workspace, repoSlug, e2.getMessage());
                    return null;
                }
            }

            ArchetypeDetector.ArchetypeInfo info = archetypeDetector.detect(ws.getRoot());
            if (info != null) {
                repoSettingsStore.updateArchetype(workspace, repoSlug, info.archetype(), info.version(),
                        info.dependencyVersions());
                LOG.infof("Detected archetype for %s/%s: %s %s", workspace, repoSlug, info.archetype(), info.version());
                return true;
            }
            LOG.debugf("No archetype detected for %s/%s", workspace, repoSlug);
            return false;

        } catch (Exception e) {
            LOG.warnf("Archetype detection failed for %s/%s: %s", workspace, repoSlug, e.getMessage());
            return null;
        }
    }

    /**
     * Builds (or rebuilds) the code graph for a single repository.
     * Returns true on success.
     */
    public boolean buildGraph(String workspace, String repoSlug) {
        String cloneUrl = platformService.buildCloneUrl(workspace, repoSlug);
        if (cloneUrl == null) {
            return false;
        }
        codeGraphStore.deleteAllForRepo(workspace, repoSlug);
        return tryBuildGraph(workspace, repoSlug, cloneUrl);
    }

    private boolean tryBuildGraph(String workspace, String repoSlug, String cloneUrl) {
        LOG.infof("Building code graph for %s/%s (default branch: %s)", workspace, repoSlug, defaultBranch);

        try (WorkspaceContext ws = WorkspaceContext.create("graph-" + workspace + "-" + repoSlug)) {
            try {
                ws.cloneRepo(cloneUrl, defaultBranch, cloneTimeoutMinutes);
            } catch (Exception e) {
                if (!"main".equals(defaultBranch)) {
                    LOG.warnf("Clone failed for %s/%s on branch '%s': %s",
                            workspace, repoSlug, defaultBranch, e.getMessage());
                    return false;
                }
                LOG.debugf("Branch 'main' not found for %s/%s, trying 'master'", workspace, repoSlug);
                try {
                    ws.cloneRepo(cloneUrl, "master", cloneTimeoutMinutes);
                } catch (Exception e2) {
                    LOG.warnf("Clone failed for %s/%s on both 'main' and 'master': %s",
                            workspace, repoSlug, e2.getMessage());
                    return false;
                }
            }

            codeGraphIndexer.indexFull(ws, workspace, repoSlug);

            ArchetypeDetector.ArchetypeInfo archetypeInfo = archetypeDetector.detect(ws.getRoot());
            if (archetypeInfo != null) {
                repoSettingsStore.updateArchetype(workspace, repoSlug, archetypeInfo.archetype(),
                        archetypeInfo.version(), archetypeInfo.dependencyVersions());
                LOG.infof("Detected archetype for %s/%s: %s %s",
                        workspace, repoSlug, archetypeInfo.archetype(), archetypeInfo.version());
            }

            if (repoSettingsStore.isVectorEnabled(workspace, repoSlug)) {
                LOG.infof("Vector indexing enabled for %s/%s — generating embeddings", workspace, repoSlug);
                embeddingIndexer.indexFull(ws, workspace, repoSlug);
            }

            LOG.infof("Code graph built successfully for %s/%s", workspace, repoSlug);
            return true;

        } catch (Exception e) {
            LOG.warnf("Graph build failed for %s/%s (non-fatal): %s",
                    workspace, repoSlug, e.getMessage());
            return false;
        }
    }

}
