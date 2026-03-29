package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public final class JiraToolSchemas {

    private JiraToolSchemas() { }

    public static Tool jiraSearchIssues() {
        return Tool.builder()
                .name("jira_search_issues")
                .description("Search Jira issues using JQL (Jira Query Language). "
                        + "Requires a linked Jira account. Use this to find issues by project, assignee, status, etc. "
                        + "Example JQL: 'project = PROJ AND status = Open AND assignee = currentUser()'")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("jql", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "JQL query string, e.g. 'project = PROJ AND status != Done'"
                                )))
                                .putAdditionalProperty("maxResults", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Maximum number of results (1-50, default 10)"
                                )))
                                .build())
                        .addRequired("jql")
                        .build())
                .build();
    }

    public static Tool jiraGetIssue() {
        return Tool.builder()
                .name("jira_get_issue")
                .description("Get full details of a single Jira issue by its key.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .build())
                        .addRequired("key")
                        .build())
                .build();
    }

    public static Tool jiraGetComments() {
        return Tool.builder()
                .name("jira_get_comments")
                .description("Get all comments for a Jira issue.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .build())
                        .addRequired("key")
                        .build())
                .build();
    }

    public static Tool jiraCreateIssue() {
        return Tool.builder()
                .name("jira_create_issue")
                .description("Create a new Jira issue in a project. Supports parent linking (for user stories under a feature/epic), "
                        + "named billing field shortcuts (billingCategory, billingCode), and a generic customFields map for any other fields.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("project", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Project key, e.g. 'PROJ'"
                                )))
                                .putAdditionalProperty("summary", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Issue summary/title"
                                )))
                                .putAdditionalProperty("description", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Issue description (optional)"
                                )))
                                .putAdditionalProperty("issueType", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Issue type: 'Task', 'Bug', 'Story', etc. (default: Task)"
                                )))
                                .putAdditionalProperty("parent", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Parent issue key to link this issue as a child, e.g. 'PROJ-10' (optional). Use for creating Stories under a Feature or Epic."
                                )))
                                .putAdditionalProperty("billingCategory", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Value for the billing-category custom field (optional). Requires jira.billing-category-field to be configured."
                                )))
                                .putAdditionalProperty("billingCode", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Value for the billing-code custom field (optional). Requires jira.billing-code-field to be configured."
                                )))
                                .putAdditionalProperty("customFields", JsonValue.from(Map.of(
                                        "type", "object",
                                        "description", "Arbitrary map of Jira custom field IDs to values, e.g. {\"customfield_10001\": \"value\"}. Use this to copy any fields from a parent issue."
                                )))
                                .build())
                        .addRequired("project")
                        .addRequired("summary")
                        .build())
                .build();
    }

    public static Tool jiraUpdateIssue() {
        return Tool.builder()
                .name("jira_update_issue")
                .description("Update an existing Jira issue. Supports summary, description, assignee (unassign by passing empty string), "
                        + "and moving the issue to another project (best-effort).")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .putAdditionalProperty("summary", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "New summary (optional)"
                                )))
                                .putAdditionalProperty("description", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "New description (optional)"
                                )))
                                .putAdditionalProperty("assignee", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira accountId to assign the issue to. Pass empty string \"\" to unassign. Omit to leave unchanged."
                                )))
                                .putAdditionalProperty("project", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Project key to move the issue to, e.g. 'NEWPROJ'. Note: the issue key will change after the move."
                                )))
                                .build())
                        .addRequired("key")
                        .build())
                .build();
    }

    public static Tool jiraAddComment() {
        return Tool.builder()
                .name("jira_add_comment")
                .description("Add a comment to a Jira issue.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .putAdditionalProperty("body", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Comment text"
                                )))
                                .build())
                        .addRequired("key")
                        .addRequired("body")
                        .build())
                .build();
    }

    public static Tool jiraTransitionIssue() {
        return Tool.builder()
                .name("jira_transition_issue")
                .description("Transition a Jira issue to a new status (e.g. 'In Progress', 'Done', 'In Review'). "
                        + "The transition must be valid for the issue's current status.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .putAdditionalProperty("transitionName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Target status name, e.g. 'In Progress', 'Done', 'In Review'"
                                )))
                                .build())
                        .addRequired("key")
                        .addRequired("transitionName")
                        .build())
                .build();
    }

    public static Tool jiraGetWorklogs() {
        return Tool.builder()
                .name("jira_get_worklogs")
                .description("Get all worklogs (time tracking entries) for a Jira issue.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .build())
                        .addRequired("key")
                        .build())
                .build();
    }

    public static Tool jiraAddWorklog() {
        return Tool.builder()
                .name("jira_add_worklog")
                .description("Add a worklog (time tracking entry) to a Jira issue.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .putAdditionalProperty("timeSpent", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Time spent, e.g. '2h 30m', '1d', '45m'"
                                )))
                                .putAdditionalProperty("comment", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Worklog comment (optional)"
                                )))
                                .putAdditionalProperty("started", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Start datetime in ISO 8601 format, e.g. '2024-01-15T09:00:00.000+0000' (optional, defaults to now)"
                                )))
                                .build())
                        .addRequired("key")
                        .addRequired("timeSpent")
                        .build())
                .build();
    }
}
