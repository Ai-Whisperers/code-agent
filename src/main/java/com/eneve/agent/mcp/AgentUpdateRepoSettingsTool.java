package com.eneve.agent.mcp;

import com.eneve.agent.agent.service.WebhookSyncService;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * MCP tool: Update repository settings (create or replace).
 */
@ApplicationScoped
public class AgentUpdateRepoSettingsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentUpdateRepoSettingsTool.class);

    @Inject
    RepoSettingsStore settingsStore;

    @Inject
    WebhookSyncService webhookSyncService;

    @Override
    public String name() {
        return "agent_update_repo_settings";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String workspaceName = (String) input.get("workspace");
        String repoSlug = (String) input.get("repoSlug");

        if (workspaceName == null || workspaceName.isBlank()) {
            return "ERROR: 'workspace' parameter is required";
        }
        if (repoSlug == null || repoSlug.isBlank()) {
            return "ERROR: 'repoSlug' parameter is required";
        }

        // Parse optional boolean fields
        Boolean reviewEnabled = parseBoolean(input.get("reviewEnabled"));
        Boolean vectorEnabled = parseBoolean(input.get("vectorEnabled"));
        Boolean docsEnabled = parseBoolean(input.get("docsEnabled"));
        Boolean upgradeEnabled = parseBoolean(input.get("upgradeEnabled"));
        Boolean qualityReportEnabled = parseBoolean(input.get("qualityReportEnabled"));
        Boolean archived = parseBoolean(input.get("archived"));

        // Parse optional string/list fields
        @SuppressWarnings("unchecked")
        List<String> ruleNames = (List<String>) input.get("ruleNames");
        String reviewPrompt = (String) input.get("reviewPrompt");

        @SuppressWarnings("unchecked")
        List<String> disabledHooks = (List<String>) input.get("disabledHooks");

        String confluenceSpaceKey = (String) input.get("confluenceSpaceKey");
        String confluenceParentPageId = (String) input.get("confluenceParentPageId");
        String gitPlatformUrl = (String) input.get("gitPlatformUrl");

        try {
            boolean enabled = reviewEnabled != null ? reviewEnabled : true;
            boolean vecEnabled = vectorEnabled != null ? vectorEnabled : false;
            boolean docEnabled = docsEnabled != null ? docsEnabled : true;
            boolean upgEnabled = upgradeEnabled != null ? upgradeEnabled : true;
            boolean qualEnabled = qualityReportEnabled != null ? qualityReportEnabled : false;
            boolean arch = archived != null ? archived : false;
            List<String> rules = ruleNames != null ? ruleNames : List.of();
            List<String> hooks = disabledHooks != null ? disabledHooks : List.of();

            settingsStore.upsert(workspaceName, repoSlug, enabled, vecEnabled, docEnabled, upgEnabled,
                    qualEnabled, arch, rules, reviewPrompt, hooks, confluenceSpaceKey, confluenceParentPageId,
                    gitPlatformUrl);

            // Sync webhooks if needed
            if (enabled && !arch) {
                try {
                    webhookSyncService.ensureWebhooks(workspaceName, repoSlug);
                } catch (Exception e) {
                    LOG.warnf("Webhook sync failed after upsert for %s/%s (non-fatal): %s",
                            workspaceName, repoSlug, e.getMessage());
                }
            } else {
                try {
                    webhookSyncService.removeWebhooks(workspaceName, repoSlug);
                } catch (Exception e) {
                    LOG.warnf("Webhook removal failed after upsert for %s/%s (non-fatal): %s",
                            workspaceName, repoSlug, e.getMessage());
                }
            }

            return "Repository settings updated for " + workspaceName + "/" + repoSlug +
                   " (reviewEnabled=" + enabled + ", archived=" + arch + ")";
        } catch (Exception e) {
            LOG.errorf("Failed to update repo settings: %s", e.getMessage());
            return "ERROR: Failed to update repo settings: " + e.getMessage();
        }
    }

    private Boolean parseBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
