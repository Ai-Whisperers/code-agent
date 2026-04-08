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
 * MCP tool: Transition a Jira issue to a new status.
 */
@ApplicationScoped
public class JiraTransitionTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(JiraTransitionTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    JiraService jiraService;

    @Override
    public String name() {
        return "jira_transition_issue";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override public boolean isDestructive() { return true; }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String issueKey = (String) input.get("key");
        String transitionName = (String) input.get("transitionName");

        if (issueKey == null || issueKey.isBlank()) {
            return "ERROR: 'key' parameter is required";
        }
        if (transitionName == null || transitionName.isBlank()) {
            return "ERROR: 'transitionName' parameter is required (e.g. 'In Progress', 'Done')";
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
            boolean success = jiraService.transitionIssue(issueKey, transitionName, creds.get());
            if (success) {
                return "Transitioned issue " + issueKey + " to: " + transitionName;
            } else {
                return "ERROR: Failed to transition issue. Available transitions: " + 
                    jiraService.listTransitions(issueKey, creds.get()).stream()
                        .map(JiraService.TransitionOption::name)
                        .toList();
            }
        } catch (Exception e) {
            LOG.errorf("Jira transition failed: %s", e.getMessage());
            return "ERROR: Failed to transition issue: " + e.getMessage();
        }
    }
}
