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

    /** Lazily normalised view of {@link #agentBaseUrl} with any trailing slashes removed. */
    private String normalizedBaseUrl() {
        return agentBaseUrl == null ? "" : agentBaseUrl.stripTrailing().replaceAll("/+$", "");
    }

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
     * Ensures that exactly one PR webhook and one comment webhook exist for the given
     * repository. Skips creation if the correct webhook URL is already registered.
     * Removes any duplicate registrations of the same URL left over from a prior bug.
     * This method is a no-op if the service is not fully configured.
     */
    public void ensureWebhooks(String repoWorkspace, String repoSlug) {
        if (!isConfigured()) {
            return;
        }

        String secret = webhookSecret.get();
        Map<String, String> existing = bitbucketPlatformService.listWebhooks(repoWorkspace, repoSlug);

        String prUrl = normalizedBaseUrl() + "/webhooks/bitbucket/pull-request";
        String commentUrl = normalizedBaseUrl() + "/webhooks/bitbucket/pull-request-comment";

        syncWebhook(repoWorkspace, repoSlug, prUrl, secret, PR_EVENTS, existing);
        syncWebhook(repoWorkspace, repoSlug, commentUrl, secret, COMMENT_EVENTS, existing);
    }

    /**
     * Ensures exactly one registration of {@code targetUrl} exists on the repository:
     * <ul>
     *   <li>0 registrations → create</li>
     *   <li>1 registration → no-op</li>
     *   <li>&gt;1 registrations → delete all duplicates and recreate one clean entry</li>
     * </ul>
     */
    private void syncWebhook(String repoWorkspace, String repoSlug,
                             String targetUrl, String secret,
                             List<String> events, Map<String, String> existing) {
        long count = existing.values().stream().filter(targetUrl::equals).count();

        if (count == 0) {
            bitbucketPlatformService.createWebhook(repoWorkspace, repoSlug, targetUrl, secret, events);
        } else if (count == 1) {
            LOG.debugf("Webhook already registered for %s/%s → %s", repoWorkspace, repoSlug, targetUrl);
        } else {
            LOG.warnf("Found %d duplicate webhooks for %s/%s → %s; removing and recreating",
                    count, repoWorkspace, repoSlug, targetUrl);
            bitbucketPlatformService.deleteWebhooksByUrl(repoWorkspace, repoSlug, targetUrl);
            bitbucketPlatformService.createWebhook(repoWorkspace, repoSlug, targetUrl, secret, events);
        }
    }

    /**
     * Removes all code-agent webhooks from the given repository, identified by their
     * {@code "code-agent"} description. Matching by description rather than by URL ensures
     * stale webhooks from a previous {@code agent.base.url} are also removed.
     *
     * <p>This method is a no-op if the service is not fully configured.
     */
    public void removeWebhooks(String repoWorkspace, String repoSlug) {
        if (!isConfigured()) {
            return;
        }

        bitbucketPlatformService.deleteWebhooksByDescription(repoWorkspace, repoSlug, "code-agent");
    }

    private boolean isConfigured() {
        if (!"bitbucket".equalsIgnoreCase(gitPlatform)) {
            return false;
        }
        String baseUrl = normalizedBaseUrl();
        if (baseUrl.isBlank() || "-".equals(baseUrl)) {
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
