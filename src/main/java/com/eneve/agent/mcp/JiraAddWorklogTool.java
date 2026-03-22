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
 * MCP tool: Add a worklog to a Jira issue.
 */
@ApplicationScoped
public class JiraAddWorklogTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(JiraAddWorklogTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    JiraService jiraService;

    @Override
    public String name() {
        return "jira_add_worklog";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String issueKey = (String) input.get("key");
        String timeSpent = (String) input.get("timeSpent");
        String comment = (String) input.get("comment");
        String started = (String) input.get("started");

        if (issueKey == null || issueKey.isBlank()) {
            return "ERROR: 'key' parameter is required";
        }
        if (timeSpent == null || timeSpent.isBlank()) {
            return "ERROR: 'timeSpent' parameter is required (e.g. '2h 30m')";
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
            jiraService.addWorklog(issueKey, timeSpent, comment, started, creds.get());
            return "Added worklog to issue: " + issueKey + " (" + timeSpent + ")";
        } catch (Exception e) {
            LOG.errorf("Jira add worklog failed: %s", e.getMessage());
            return "ERROR: Failed to add worklog: " + e.getMessage();
        }
    }
}
