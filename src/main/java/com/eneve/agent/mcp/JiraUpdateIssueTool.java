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
 * MCP tool: Update an existing Jira issue.
 */
@ApplicationScoped
public class JiraUpdateIssueTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(JiraUpdateIssueTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    JiraService jiraService;

    @Override
    public String name() {
        return "jira_update_issue";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override public boolean isDestructive() { return true; }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String issueKey = (String) input.get("key");
        String summary = (String) input.get("summary");
        String description = (String) input.get("description");
        String assignee = (String) input.get("assignee");
        String project = (String) input.get("project");

        if (issueKey == null || issueKey.isBlank()) {
            return "ERROR: 'key' parameter is required";
        }
        boolean nothingToUpdate = (summary == null || summary.isBlank())
                && description == null
                && assignee == null
                && (project == null || project.isBlank());
        if (nothingToUpdate) {
            return "ERROR: At least one of 'summary', 'description', 'assignee', or 'project' must be provided";
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
            jiraService.updateIssue(issueKey, summary, description, assignee, project, creds.get());
            return "Updated issue: " + issueKey;
        } catch (Exception e) {
            LOG.errorf("Jira update issue failed: %s", e.getMessage());
            return "ERROR: Failed to update issue: " + e.getMessage();
        }
    }
}
