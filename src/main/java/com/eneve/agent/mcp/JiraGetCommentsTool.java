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
 * MCP tool: Get comments for a Jira issue.
 */
@ApplicationScoped
public class JiraGetCommentsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(JiraGetCommentsTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    JiraService jiraService;

    @Override
    public String name() {
        return "jira_get_comments";
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
            java.util.List<String> comments = jiraService.getComments(issueKey, creds.get());
            if (comments.isEmpty()) {
                return "No comments found for issue: " + issueKey;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Comments for ").append(issueKey).append(":\n\n");
            for (int i = 0; i < comments.size(); i++) {
                sb.append(i + 1).append(". ").append(comments.get(i)).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Jira get comments failed: %s", e.getMessage());
            return "ERROR: Failed to get comments: " + e.getMessage();
        }
    }
}
