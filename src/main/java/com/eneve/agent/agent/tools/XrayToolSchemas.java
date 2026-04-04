package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.eneve.agent.xray.XrayTestRunStatus;

import java.util.Map;

public final class XrayToolSchemas {

    private XrayToolSchemas() { }

    public static Tool xraySearchTests() {
        return Tool.builder()
                .name("xray_search_tests")
                .description("Search Xray test cases using JQL (Jira Query Language). "
                        + "Requires a linked Xray Cloud account. "
                        + "Example JQL: 'project = PROJ AND labels = regression'")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("jql", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "JQL query to filter test cases, e.g. 'project = PROJ AND labels = smoke'"
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

    public static Tool xraySearchExecutions() {
        return Tool.builder()
                .name("xray_search_executions")
                .description("Search Xray test executions using JQL. "
                        + "Requires a linked Xray Cloud account. "
                        + "Example JQL: 'project = PROJ AND created >= -7d'")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("jql", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "JQL query to filter test executions, e.g. 'project = PROJ AND created >= -7d'"
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

    public static Tool xrayGetTestExecution() {
        return Tool.builder()
                .name("xray_get_test_execution")
                .description("Get full details of a single Xray test execution including all test run results and statuses. "
                        + "Requires a linked Xray Cloud account. "
                        + "Use the returned run IDs with xray_update_test_run_status.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("issueKey", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key of the test execution, e.g. 'PROJ-456'"
                                )))
                                .build())
                        .addRequired("issueKey")
                        .build())
                .build();
    }

    public static Tool xrayGetTestCoverage() {
        return Tool.builder()
                .name("xray_get_test_coverage")
                .description("Check which Jira issues (user stories, features) have linked Xray test cases. "
                        + "Requires a linked Xray Cloud account. "
                        + "Returns covered vs uncovered issues and the linked test cases.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("issueKeys", JsonValue.from(Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "List of Jira issue keys to check for test coverage, e.g. ['PROJ-1', 'PROJ-2']. Maximum 20."
                                )))
                                .build())
                        .addRequired("issueKeys")
                        .build())
                .build();
    }

    public static Tool xrayCreateTestExecution() {
        return Tool.builder()
                .name("xray_create_test_execution")
                .description("Create a new Xray test execution in Jira. "
                        + "Requires a linked Xray Cloud account with write permissions. "
                        + "Optionally add existing test cases by their Jira issue IDs.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("projectKey", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira project key, e.g. 'PROJ'"
                                )))
                                .putAdditionalProperty("summary", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Summary/title for the test execution issue"
                                )))
                                .putAdditionalProperty("testIssueIds", JsonValue.from(Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "Optional list of Jira issue IDs (numeric strings) for the test cases to include"
                                )))
                                .build())
                        .addRequired("projectKey")
                        .addRequired("summary")
                        .build())
                .build();
    }

    public static Tool xrayUpdateTestRunStatus() {
        return Tool.builder()
                .name("xray_update_test_run_status")
                .description("Update the status of a single test run within an Xray test execution. "
                        + "Requires a linked Xray Cloud account with write permissions. "
                        + "Use xray_get_test_execution to obtain the testRunId.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("testRunId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Internal Xray test run ID (from xray_get_test_execution)"
                                )))
                                .putAdditionalProperty("status", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", XrayTestRunStatus.NAME_LIST,
                                        "description", "New status for the test run"
                                )))
                                .build())
                        .addRequired("testRunId")
                        .addRequired("status")
                        .build())
                .build();
    }
}
