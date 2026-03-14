package com.eneve.agent.agent;

import java.util.List;

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
    @Inject RepoSettingsStore repoSettingsStore;

    @ConfigProperty(name = "git.username")
    String gitUser;

    @ConfigProperty(name = "git.password")
    String gitPassword;

    @ConfigProperty(name = "git.platform", defaultValue = "bitbucket")
    String gitPlatform;

    @ConfigProperty(name = "code-graph.scheduler.default-branch", defaultValue = "main")
    String defaultBranch;

    @ConfigProperty(name = "code-graph.scheduler.clone-timeout-minutes", defaultValue = "10")
    long cloneTimeoutMinutes;

    public record BuildResult(int built, int skipped, int alreadyPresent) {}

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

            String cloneUrl = buildCloneUrl(repo.workspace(), repo.repoSlug());
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
     * Builds (or rebuilds) the code graph for a single repository.
     * Returns true on success.
     */
    public boolean buildGraph(String workspace, String repoSlug) {
        String cloneUrl = buildCloneUrl(workspace, repoSlug);
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
            LOG.infof("Code graph built successfully for %s/%s", workspace, repoSlug);
            return true;

        } catch (Exception e) {
            LOG.warnf("Graph build failed for %s/%s (non-fatal): %s",
                    workspace, repoSlug, e.getMessage());
            return false;
        }
    }

    String buildCloneUrl(String workspace, String repoSlug) {
        return switch (gitPlatform.toLowerCase()) {
            case "bitbucket" -> "https://" + gitUser + ":" + gitPassword
                    + "@bitbucket.org/" + workspace + "/" + repoSlug + ".git";
            case "gitlab" -> "https://" + gitUser + ":" + gitPassword
                    + "@gitlab.com/" + workspace + "/" + repoSlug + ".git";
            case "azuredevops" -> {
                LOG.debugf("Graph build for Azure DevOps repos requires project field — "
                        + "skipping %s/%s. Graph will be built at first review.", workspace, repoSlug);
                yield null;
            }
            default -> {
                LOG.warnf("Unknown git.platform '%s' — cannot build clone URL for %s/%s",
                        gitPlatform, workspace, repoSlug);
                yield null;
            }
        };
    }
}
