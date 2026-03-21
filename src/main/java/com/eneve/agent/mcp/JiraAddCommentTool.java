package com.eneve.agent.mcp;

import java.util.Map;
import java.util.Optional;

import com.eneve.agent.jira.JiraService;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * MCP tool: Add a comment to a Jira issue.
 */
@ApplicationScoped
public class JiraAddCommentTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(JiraAddCommentTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    JiraService jiraService;

    @Override
    public String name() {
        return "jira_add_comment";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String issueKey = (String) input.get("key");
        String body = (String) input.get("body");

        if (issueKey == null || issueKey.isBlank()) {
            return "ERROR: 'key' parameter is required";
        }
        if (body == null || body.isBlank()) {
            return "ERROR: 'body' parameter is required";
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
            jiraService.addComment(issueKey, body, creds.get());
            return "Added comment to issue: " + issueKey;
        } catch (Exception e) {
            LOG.errorf("Jira add comment failed: %s", e.getMessage());
            return "ERROR: Failed to add comment: " + e.getMessage();
        }
    }
}
