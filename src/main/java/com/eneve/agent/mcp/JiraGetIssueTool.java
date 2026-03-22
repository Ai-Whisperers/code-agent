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
 * MCP tool: Get a single Jira issue by key.
 */
@ApplicationScoped
public class JiraGetIssueTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(JiraGetIssueTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    JiraService jiraService;

    @Override
    public String name() {
        return "jira_get_issue";
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
            JiraService.JiraIssue issue = jiraService.getIssue(issueKey, creds.get());
            if (issue == null) {
                return "Issue not found: " + issueKey;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Issue: ").append(issue.key()).append("\n");
            sb.append("Summary: ").append(issue.summary()).append("\n");
            sb.append("Status: ").append(issue.status()).append("\n");
            sb.append("Type: ").append(issue.issueType()).append("\n");
            sb.append("Project: ").append(issue.projectKey()).append("\n\n");
            if (issue.description() != null && !issue.description().isBlank()) {
                sb.append("Description:\n").append(issue.description()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Jira get issue failed: %s", e.getMessage());
            return "ERROR: Failed to get Jira issue: " + e.getMessage();
        }
    }
}
