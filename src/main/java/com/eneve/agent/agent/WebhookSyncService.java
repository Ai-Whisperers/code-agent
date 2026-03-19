package com.eneve.agent.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.eneve.agent.scm.bitbucket.BitbucketPlatformService;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Reconciles Bitbucket webhooks against repo_settings on startup, and provides
 * {@link #ensureWebhooks} / {@link #removeWebhooks} helpers used by the settings API.
 *
 * <p>The service is a no-op when any of the following conditions is not met:
 * <ul>
 *   <li>{@code git.platform} is {@code bitbucket}</li>
 *   <li>{@code agent.base.url} is configured and non-blank</li>
 *   <li>{@code webhook.secret.bitbucket} is configured, non-blank, and not {@code -}</li>
 *   <li>{@code bitbucket.workspace} is configured and non-blank</li>
 * </ul>
 */
@ApplicationScoped
public class WebhookSyncService {

    private static final Logger LOG = Logger.getLogger(WebhookSyncService.class);

    private static final List<String> PR_EVENTS =
            List.of("pullrequest:created", "pullrequest:updated", "pullrequest:fulfilled");
    private static final List<String> COMMENT_EVENTS =
            List.of("pullrequest:comment_created");

    @Inject
    BitbucketPlatformService bitbucketPlatformService;

    @Inject
    RepoSettingsStore settingsStore;

    @ConfigProperty(name = "git.platform", defaultValue = "bitbucket")
    String gitPlatform;

    @ConfigProperty(name = "agent.base.url", defaultValue = "")
    String agentBaseUrl;

    @ConfigProperty(name = "webhook.secret.bitbucket")
    Optional<String> webhookSecret;

    @ConfigProperty(name = "bitbucket.workspace", defaultValue = "")
    String workspace;

    void onStartup(@Observes StartupEvent event) {
        if (!isConfigured()) {
            LOG.info("Webhook sync skipped — agent.base.url or webhook.secret.bitbucket not configured");
            return;
        }

        List<RepoSettings> repos = settingsStore.listAll();
        if (repos.isEmpty()) {
            LOG.info("Webhook sync skipped — no repos in repo_settings yet");
            return;
        }

        int ensured = 0;
        int removed = 0;
        for (RepoSettings repo : repos) {
            try {
                if (repo.reviewEnabled() && !repo.archived()) {
                    ensureWebhooks(repo.workspace(), repo.repoSlug());
                    ensured++;
                } else {
                    removeWebhooks(repo.workspace(), repo.repoSlug());
                    removed++;
                }
            } catch (Exception e) {
                LOG.warnf("Webhook sync failed for %s/%s (non-fatal): %s",
                        repo.workspace(), repo.repoSlug(), e.getMessage());
            }
        }

        LOG.infof("Webhook sync complete: %d repos ensured, %d repos cleaned up", ensured, removed);
    }

    /**
     * Ensures that both the PR and comment webhooks exist for the given repository.
     * Skips creation if the webhook URL is already registered. This method is a no-op
     * if the service is not fully configured.
     */
    public void ensureWebhooks(String repoWorkspace, String repoSlug) {
        if (!isConfigured()) {
            return;
        }

        String secret = webhookSecret.get();
        Map<String, String> existing = bitbucketPlatformService.listWebhooks(repoWorkspace, repoSlug);
        java.util.Set<String> registeredUrls = new java.util.HashSet<>(existing.values());

        String prUrl = agentBaseUrl + "/webhooks/bitbucket/pull-request";
        if (!registeredUrls.contains(prUrl)) {
            bitbucketPlatformService.createWebhook(repoWorkspace, repoSlug, prUrl, secret, PR_EVENTS);
        } else {
            LOG.debugf("PR webhook already registered for %s/%s", repoWorkspace, repoSlug);
        }

        String commentUrl = agentBaseUrl + "/webhooks/bitbucket/pull-request-comment";
        if (!registeredUrls.contains(commentUrl)) {
            bitbucketPlatformService.createWebhook(repoWorkspace, repoSlug, commentUrl, secret, COMMENT_EVENTS);
        } else {
            LOG.debugf("Comment webhook already registered for %s/%s", repoWorkspace, repoSlug);
        }
    }

    /**
     * Removes the agent's PR and comment webhooks from the given repository.
     * This method is a no-op if the service is not fully configured or if the hooks
     * are not registered.
     */
    public void removeWebhooks(String repoWorkspace, String repoSlug) {
        if (!isConfigured()) {
            return;
        }

        String prUrl = agentBaseUrl + "/webhooks/bitbucket/pull-request";
        String commentUrl = agentBaseUrl + "/webhooks/bitbucket/pull-request-comment";

        bitbucketPlatformService.deleteWebhooksByUrl(repoWorkspace, repoSlug, prUrl);
        bitbucketPlatformService.deleteWebhooksByUrl(repoWorkspace, repoSlug, commentUrl);
    }

    private boolean isConfigured() {
        if (!"bitbucket".equalsIgnoreCase(gitPlatform)) {
            return false;
        }
        if (agentBaseUrl == null || agentBaseUrl.isBlank() || "-".equals(agentBaseUrl)) {
            return false;
        }
        if (workspace == null || workspace.isBlank()) {
            return false;
        }
        if (webhookSecret.isEmpty()) {
            return false;
        }
        String secret = webhookSecret.get();
        return !secret.isBlank() && !"-".equals(secret);
    }
}
