package com.eneve.agent.mcp;

import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * MCP tool: Get settings for a specific repository.
 */
@ApplicationScoped
public class AgentGetRepoSettingsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentGetRepoSettingsTool.class);

    @Inject
    RepoSettingsStore settingsStore;

    @Override
    public String name() {
        return "agent_get_repo_settings";
    }

    @Override
    public boolean isReadOnly() {
        return true;
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

        try {
            Optional<RepoSettings> settingsOpt = settingsStore.find(workspaceName, repoSlug);
            if (settingsOpt.isEmpty()) {
                return "No settings found for " + workspaceName + "/" + repoSlug;
            }

            RepoSettings s = settingsOpt.get();
            StringBuilder sb = new StringBuilder();
            sb.append("Repository Settings: ").append(workspaceName).append("/").append(repoSlug).append("\n\n");
            sb.append("Review Enabled: ").append(s.reviewEnabled()).append("\n");
            sb.append("Vector Enabled: ").append(s.vectorEnabled()).append("\n");
            sb.append("Docs Enabled: ").append(s.docsEnabled()).append("\n");
            sb.append("Upgrade Enabled: ").append(s.upgradeEnabled()).append("\n");
            sb.append("Quality Report Enabled: ").append(s.qualityReportEnabled()).append("\n");
            sb.append("Archived: ").append(s.archived()).append("\n");

            if (s.ruleNames() != null && !s.ruleNames().isEmpty()) {
                sb.append("Rule Names: ").append(String.join(", ", s.ruleNames())).append("\n");
            }

            if (s.reviewPrompt() != null && !s.reviewPrompt().isBlank()) {
                sb.append("Review Prompt: (custom)\n");
            }

            if (s.disabledHooks() != null && !s.disabledHooks().isEmpty()) {
                sb.append("Disabled Hooks: ").append(String.join(", ", s.disabledHooks())).append("\n");
            }

            if (s.confluenceSpaceKey() != null) {
                sb.append("Confluence Space: ").append(s.confluenceSpaceKey()).append("\n");
            }

            if (s.archetype() != null) {
                sb.append("Archetype: ").append(s.archetype());
                if (s.archetypeVersion() != null) {
                    sb.append(" (").append(s.archetypeVersion()).append(")");
                }
                sb.append("\n");
            }

            if (s.gitPlatformUrl() != null) {
                sb.append("Git Platform URL: ").append(s.gitPlatformUrl()).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Failed to get repo settings: %s", e.getMessage());
            return "ERROR: Failed to get repo settings: " + e.getMessage();
        }
    }
}
