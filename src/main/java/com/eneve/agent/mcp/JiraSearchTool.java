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
 * MCP tool: Search Jira issues using JQL.
 */
@ApplicationScoped
public class JiraSearchTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(JiraSearchTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    JiraService jiraService;

    @Override
    public String name() {
        return "jira_search_issues";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String jql = (String) input.get("jql");
        if (jql == null || jql.isBlank()) {
            return "ERROR: 'jql' parameter is required (e.g. 'project = PROJ AND status = Open')";
        }

        int maxResults = 10;
        Object maxObj = input.get("maxResults");
        if (maxObj instanceof Number n) {
            maxResults = Math.min(Math.max(1, n.intValue()), 50);
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
            java.util.List<JiraService.JiraIssueDetail> issues = jiraService.searchIssues(jql, maxResults, creds.get());
            if (issues.isEmpty()) {
                return "No issues found for JQL: " + jql;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(issues.size()).append(" issue(s):\n\n");
            for (JiraService.JiraIssueDetail issue : issues) {
                sb.append("- ").append(issue.key()).append(": ").append(issue.summary()).append("\n");
                sb.append("  Status: ").append(issue.status()).append("\n");
                if (issue.assignee() != null && !issue.assignee().isBlank()) {
                    sb.append("  Assignee: ").append(issue.assignee()).append("\n");
                }
                if (issue.description() != null && !issue.description().isBlank()) {
                    String desc = issue.description().length() > 200
                            ? issue.description().substring(0, 200) + "..."
                            : issue.description();
                    sb.append("  Description: ").append(desc.replace("\n", " ")).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Jira search failed: %s", e.getMessage());
            return "ERROR: Failed to search Jira: " + e.getMessage();
        }
    }
}
