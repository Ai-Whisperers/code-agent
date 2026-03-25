package com.eneve.agent.mcp;

import com.eneve.agent.jira.JiraService;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * MCP tool: Create a new Jira issue.
 */
@ApplicationScoped
public class JiraCreateIssueTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(JiraCreateIssueTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    JiraService jiraService;

    @Inject
    SettingsService settings;

    @Override
    public String name() {
        return "jira_create_issue";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @SuppressWarnings("unchecked")
    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String projectKey = (String) input.get("project");
        String summary = (String) input.get("summary");
        String description = (String) input.get("description");
        String issueType = (String) input.getOrDefault("issueType", "Task");
        String parentKey = (String) input.get("parent");
        String billingCategory = (String) input.get("billingCategory");
        String billingCode = (String) input.get("billingCode");
        Map<String, Object> customFields = null;
        Object cf = input.get("customFields");
        if (cf instanceof Map<?, ?> rawMap) {
            customFields = (Map<String, Object>) rawMap;
        }

        if (projectKey == null || projectKey.isBlank()) {
            return "ERROR: 'project' parameter is required (project key)";
        }
        if (summary == null || summary.isBlank()) {
            return "ERROR: 'summary' parameter is required";
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
            String createdKey = jiraService.createIssue(
                    projectKey, summary, description, issueType,
                    parentKey, billingCategory, billingCode,
                    settings.get("jira.billing-category-field", ""),
                    settings.get("jira.billing-code-field", ""),
                    customFields, creds.get());
            if (createdKey == null) {
                return "ERROR: Failed to create issue";
            }
            return "Created issue: " + createdKey;
        } catch (Exception e) {
            LOG.errorf("Jira create issue failed: %s", e.getMessage());
            return "ERROR: Failed to create issue: " + e.getMessage();
        }
    }
}
