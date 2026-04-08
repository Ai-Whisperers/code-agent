package com.eneve.agent.scm;

import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.scm.azuredevops.AzureDevOpsPlatformService;
import com.eneve.agent.scm.bitbucket.BitbucketPlatformService;
import com.eneve.agent.scm.github.GitHubPlatformService;
import com.eneve.agent.scm.gitlab.GitLabPlatformService;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Resolves the correct {@link GitPlatformService} implementation from a repository URL
 * or an explicit {@link RepoCoordinates.Platform} value, enabling multiple SCM platforms
 * to coexist within a single deployment.
 * <p>
 * Callers that process jobs (handlers, services) should inject this registry and call
 * {@link #resolve(String)} with the job's repository URL instead of injecting the
 * {@link GitPlatformService} interface directly. Contexts that do not have a repo URL
 * (e.g., webhook resources that are already platform-specific) continue to inject
 * {@link GitPlatformService} via the CDI producer.
 */
@ApplicationScoped
public class GitPlatformRegistry {

    private static final Logger LOG = Logger.getLogger(GitPlatformRegistry.class);

    @Inject BitbucketPlatformService bitbucket;
    @Inject AzureDevOpsPlatformService azureDevOps;
    @Inject GitLabPlatformService gitlab;
    @Inject GitHubPlatformService github;

    @Inject SettingsService settings;

    /**
     * Resolve the correct platform service from any repository clone URL.
     * Falls back to the default configured platform if the URL cannot be parsed.
     */
    public GitPlatformService resolve(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            LOG.warn("resolve() called with blank repoUrl — falling back to default platform");
            return defaultPlatform();
        }
        try {
            RepoCoordinates coords = RepoCoordinates.parse(repoUrl);
            return resolve(coords.platform());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Cannot determine platform from repoUrl '%s' (%s) — falling back to default",
                    repoUrl, e.getMessage());
            return defaultPlatform();
        }
    }

    /**
     * Resolve the correct platform service from an explicit platform enum value.
     */
    public GitPlatformService resolve(RepoCoordinates.Platform platform) {
        return switch (platform) {
            case BITBUCKET    -> bitbucket;
            case AZURE_DEVOPS -> azureDevOps;
            case GITLAB       -> gitlab;
            case GITHUB       -> github;
        };
    }

    /**
     * Returns the default platform service as configured by the {@code git.platform} setting.
     * Used as a fallback when no repo URL is available and by contexts that are
     * inherently single-platform (e.g., scheduled jobs that iterate all repos).
     */
    public GitPlatformService defaultPlatform() {
        String platform = settings.get("git.platform", "bitbucket");
        return switch (platform.toLowerCase().trim()) {
            case "azuredevops", "azure-devops", "azure" -> azureDevOps;
            case "gitlab", "gitlab-cloud"               -> gitlab;
            case "github"                               -> github;
            default                                     -> bitbucket;
        };
    }
}
