package com.eneve.agent.scm;

import com.eneve.agent.scm.azuredevops.AzureDevOpsPlatformService;
import com.eneve.agent.scm.bitbucket.BitbucketPlatformService;
import com.eneve.agent.scm.github.GitHubPlatformService;
import com.eneve.agent.scm.gitlab.GitLabPlatformService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * CDI producer that selects the {@link GitPlatformService} implementation
 * based on the {@code git.platform} configuration property.
 */
@ApplicationScoped
public class GitPlatformProducer {

    private static final Logger LOG = Logger.getLogger(GitPlatformProducer.class);

    @ConfigProperty(name = "git.platform", defaultValue = "bitbucket")
    String platform;

    @Inject BitbucketPlatformService bitbucket;
    @Inject AzureDevOpsPlatformService azureDevOps;
    @Inject GitLabPlatformService gitlab;
    @Inject GitHubPlatformService github;

    @Produces
    @ApplicationScoped
    public GitPlatformService gitPlatformService() {
        return switch (platform.toLowerCase().trim()) {
            case "bitbucket" -> {
                LOG.info("Git platform: Bitbucket Cloud");
                yield bitbucket;
            }
            case "azuredevops", "azure-devops", "azure" -> {
                LOG.info("Git platform: Azure DevOps");
                yield azureDevOps;
            }
            case "gitlab", "gitlab-cloud" -> {
                LOG.info("Git platform: GitLab Cloud");
                yield gitlab;
            }
            case "github" -> {
                LOG.info("Git platform: GitHub");
                yield github;
            }
            default -> throw new IllegalArgumentException(
                    "Unknown git.platform value: '" + platform
                            + "'. Supported values: bitbucket, azuredevops, gitlab, github");
        };
    }
}
