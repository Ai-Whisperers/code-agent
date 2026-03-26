package com.eneve.agent.agent;

import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link ToolDefinitions#chat} overloads introduced to
 * support lazy-loading of AWS tools and role-based write-tool filtering.
 */
class ToolDefinitionsChatTest {

    private static final Set<String> AWS_TOOL_NAMES = Set.of(
            "aws_cloudwatch_logs", "aws_ecs", "aws_cloudwatch_metrics", "aws_rds");

    private static final Set<String> WRITE_TOOL_NAMES = Set.of(
            "jira_create_issue", "jira_update_issue", "jira_add_comment",
            "jira_transition_issue", "jira_add_worklog",
            "confluence_create_page", "confluence_update_page",
            "agent_run_fix", "agent_get_job_status", "agent_submit_review_job");

    private static final Set<String> CORE_TOOL_NAMES = Set.of(
            "search_knowledge_base", "lookup_customer_context", "set_product_context",
            "semantic_search", "search_code", "query_code_graph", "fetch_url",
            "jira_search_issues", "jira_get_issue", "jira_get_comments", "jira_get_worklogs",
            "confluence_search", "confluence_get_page");

    // ─── core tools always present ────────────────────────────────────────────────

    @Test
    void allCoreToolsPresent_withAws_withWrite() {
        Set<String> names = toolNames(ToolDefinitions.chat(true, true));
        assertTrue(names.containsAll(CORE_TOOL_NAMES),
                "All core tools must be present regardless of flags");
    }

    @Test
    void allCoreToolsPresent_withoutAws_withoutWrite() {
        Set<String> names = toolNames(ToolDefinitions.chat(false, false));
        assertTrue(names.containsAll(CORE_TOOL_NAMES),
                "Core tools must be present even when both flags are false");
    }

    // ─── AWS tools ────────────────────────────────────────────────────────────────

    @Test
    void awsToolsIncluded_whenFlagIsTrue() {
        Set<String> names = toolNames(ToolDefinitions.chat(false, true));
        assertTrue(names.containsAll(AWS_TOOL_NAMES),
                "All 4 AWS tools must be present when includeAwsTools=true");
    }

    @Test
    void awsToolsExcluded_whenFlagIsFalse() {
        Set<String> names = toolNames(ToolDefinitions.chat(false, false));
        assertTrue(names.stream().noneMatch(AWS_TOOL_NAMES::contains),
                "No AWS tools must appear when includeAwsTools=false");
    }

    @Test
    void awsToolsExcluded_doesNotAffectCoreTools() {
        Set<String> withAws = toolNames(ToolDefinitions.chat(false, true));
        Set<String> withoutAws = toolNames(ToolDefinitions.chat(false, false));

        // everything except the AWS tools should be the same
        withAws.removeAll(AWS_TOOL_NAMES);
        assertEquals(withAws, withoutAws,
                "Removing AWS flag must only affect AWS tools, not core tools");
    }

    // ─── write / job-execution tools ─────────────────────────────────────────────

    @Test
    void writeToolsIncluded_whenCanExecuteJobsIsTrue() {
        Set<String> names = toolNames(ToolDefinitions.chat(true, false));
        assertTrue(names.containsAll(WRITE_TOOL_NAMES),
                "All write tools must be present when canExecuteJobs=true");
    }

    @Test
    void writeToolsExcluded_whenCanExecuteJobsIsFalse() {
        Set<String> names = toolNames(ToolDefinitions.chat(false, false));
        assertTrue(names.stream().noneMatch(WRITE_TOOL_NAMES::contains),
                "No write tools must appear when canExecuteJobs=false");
    }

    // ─── single-arg overload compatibility ───────────────────────────────────────

    @Test
    void singleArgOverload_includesAwsToolsByDefault() {
        Set<String> singleArg = toolNames(ToolDefinitions.chat(true));
        Set<String> explicit  = toolNames(ToolDefinitions.chat(true, true));
        assertEquals(explicit, singleArg,
                "chat(boolean) must delegate to chat(boolean, true)");
    }

    @Test
    void noArgOverload_includesAwsAndWriteTools() {
        Set<String> names = toolNames(ToolDefinitions.chat());
        assertTrue(names.containsAll(AWS_TOOL_NAMES));
        assertTrue(names.containsAll(WRITE_TOOL_NAMES));
    }

    // ─── no duplicates ────────────────────────────────────────────────────────────

    @Test
    void noDuplicateToolNames_fullSet() {
        List<String> names = ToolDefinitions.chat(true, true).stream()
                .map(this::toolName)
                .toList();
        assertEquals(names.size(), Set.copyOf(names).size(),
                "Tool list must not contain duplicate names");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    private Set<String> toolNames(List<ToolUnion> tools) {
        return tools.stream()
                .map(this::toolName)
                .collect(Collectors.toSet());
    }

    private String toolName(ToolUnion tu) {
        Tool tool = tu.tool().orElseThrow(() ->
                new AssertionError("Expected a Tool inside ToolUnion"));
        return tool.name();
    }
}
