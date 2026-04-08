package com.eneve.agent.mcp;

import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * MCP tool: List all configured repositories.
 */
@ApplicationScoped
public class AgentListReposTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentListReposTool.class);

    @Inject
    RepoSettingsStore settingsStore;

    @Override
    public String name() {
        return "agent_list_repos";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        try {
            List<RepoSettings> repos = settingsStore.listAll();

            if (repos.isEmpty()) {
                return "No repositories configured.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Configured Repositories (").append(repos.size()).append("):\n\n");

            for (RepoSettings repo : repos) {
                sb.append("- ").append(repo.workspace()).append("/").append(repo.repoSlug()).append("\n");
                sb.append("  Review Enabled: ").append(repo.reviewEnabled()).append("\n");
                sb.append("  Vector Enabled: ").append(repo.vectorEnabled()).append("\n");
                sb.append("  Docs Enabled: ").append(repo.docsEnabled()).append("\n");
                sb.append("  Upgrade Enabled: ").append(repo.upgradeEnabled()).append("\n");
                sb.append("  Quality Report Enabled: ").append(repo.qualityReportEnabled()).append("\n");
                sb.append("  Archived: ").append(repo.archived()).append("\n");
                if (repo.archetype() != null) {
                    sb.append("  Archetype: ").append(repo.archetype()).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Failed to list repositories: %s", e.getMessage());
            return "ERROR: Failed to list repositories: " + e.getMessage();
        }
    }
}
