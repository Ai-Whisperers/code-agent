package com.eneve.agent.mcp;

import com.eneve.agent.jira.JiraService;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * MCP tool: Get worklogs for a Jira issue.
 */
@ApplicationScoped
public class JiraGetWorklogsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(JiraGetWorklogsTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    JiraService jiraService;

    @Override
    public String name() {
        return "jira_get_worklogs";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String issueKey = (String) input.get("key");
        if (issueKey == null || issueKey.isBlank()) {
            return "ERROR: 'key' parameter is required (e.g. 'PROJ-123')";
        }

        String userId = workspace.getUserId();
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return "ERROR: No authenticated user. Please link your Jira account in Settings > MCP Profiles.";
        }

        Optional<JiraService.JiraCredentials> creds = linkedAccountService.resolveJira(userId);
        if (creds.isEmpty()) {
            return "ERROR: No Jira account linked. Please link your Jira account in Settings > MCP Profiles.";
        }

        try {
            java.util.List<JiraService.WorklogEntry> worklogs = jiraService.getWorklogs(issueKey, creds.get());
            if (worklogs.isEmpty()) {
                return "No worklogs found for issue: " + issueKey;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Worklogs for ").append(issueKey).append(":\n\n");
            for (JiraService.WorklogEntry w : worklogs) {
                sb.append("- ").append(w.author()).append(": ").append(w.timeSpent());
                if (w.started() != null && !w.started().isBlank()) {
                    sb.append(" (").append(w.started()).append(")");
                }
                if (w.comment() != null && !w.comment().isBlank()) {
                    sb.append(" - ").append(w.comment());
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Jira get worklogs failed: %s", e.getMessage());
            return "ERROR: Failed to get worklogs: " + e.getMessage();
        }
    }
}
