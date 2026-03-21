package com.eneve.agent.mcp;

import java.util.Map;
import java.util.Optional;

import com.eneve.agent.agent.RepoSettings;
import com.eneve.agent.agent.RepoSettingsStore;
import com.eneve.agent.agent.WebhookSyncService;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * MCP tool: Enable or disable automated review for a repository.
 */
@ApplicationScoped
public class AgentSetRepoReviewEnabledTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentSetRepoReviewEnabledTool.class);

    @Inject
    RepoSettingsStore settingsStore;

    @Inject
    WebhookSyncService webhookSyncService;

    @Override
    public String name() {
        return "agent_set_repo_review_enabled";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String workspaceName = (String) input.get("workspace");
        String repoSlug = (String) input.get("repoSlug");
        Object enabledObj = input.get("enabled");

        if (workspaceName == null || workspaceName.isBlank()) {
            return "ERROR: 'workspace' parameter is required";
        }
        if (repoSlug == null || repoSlug.isBlank()) {
            return "ERROR: 'repoSlug' parameter is required";
        }
        if (enabledObj == null) {
            return "ERROR: 'enabled' parameter is required (boolean)";
        }

        boolean enabled;
        if (enabledObj instanceof Boolean b) {
            enabled = b;
        } else {
            enabled = Boolean.parseBoolean(enabledObj.toString());
        }

        try {
            Optional<RepoSettings> existing = settingsStore.find(workspaceName, repoSlug);
            if (existing.isEmpty()) {
                return "ERROR: No settings found for " + workspaceName + "/" + repoSlug;
            }

            settingsStore.setReviewEnabled(workspaceName, repoSlug, enabled);

            // Sync webhooks
            if (enabled && !existing.get().archived()) {
                try {
                    webhookSyncService.ensureWebhooks(workspaceName, repoSlug);
                } catch (Exception e) {
                    LOG.warnf("Webhook sync failed after enable for %s/%s (non-fatal): %s",
                            workspaceName, repoSlug, e.getMessage());
                }
            } else {
                try {
                    webhookSyncService.removeWebhooks(workspaceName, repoSlug);
                } catch (Exception e) {
                    LOG.warnf("Webhook sync failed after disable for %s/%s (non-fatal): %s",
                            workspaceName, repoSlug, e.getMessage());
                }
            }

            return "Repository review " + (enabled ? "enabled" : "disabled") +
                   " for " + workspaceName + "/" + repoSlug;
        } catch (Exception e) {
            LOG.errorf("Failed to set repo review enabled: %s", e.getMessage());
            return "ERROR: Failed to set repo review enabled: " + e.getMessage();
        }
    }
}
