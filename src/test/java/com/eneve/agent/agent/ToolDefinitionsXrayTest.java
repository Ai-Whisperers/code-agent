package com.eneve.agent.agent;

import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link ToolDefinitions#xrayTools()} list.
 *
 * <p>Verifies:
 * <ul>
 *   <li>All six expected tool names are present</li>
 *   <li>No duplicates within the list</li>
 *   <li>Each schema has the correct required parameters</li>
 *   <li>Xray tools are NOT present in any default toolset (chat, planExecution, all, readOnly)</li>
 * </ul>
 */
class ToolDefinitionsXrayTest {

    private static final Set<String> EXPECTED_XRAY_TOOL_NAMES = Set.of(
            "xray_search_tests",
            "xray_search_executions",
            "xray_get_test_execution",
            "xray_get_test_coverage",
            "xray_create_test_execution",
            "xray_update_test_run_status"
    );

    // ─── List completeness ────────────────────────────────────────────────────────

    @Test
    void xrayTools_containsExactlySixTools() {
        assertEquals(6, ToolDefinitions.xrayTools().size());
    }

    @Test
    void xrayTools_containsAllExpectedNames() {
        Set<String> actual = toolNames(ToolDefinitions.xrayTools());
        assertEquals(EXPECTED_XRAY_TOOL_NAMES, actual);
    }

    @Test
    void xrayTools_noDuplicates() {
        List<String> names = toolNames(ToolDefinitions.xrayTools()).stream().toList();
        assertEquals(names.size(), Set.copyOf(names).size());
    }

    // ─── Required parameters per schema ──────────────────────────────────────────

    @Test
    void xraySearchTests_hasRequiredJqlParam() {
        Tool schema = findTool("xray_search_tests");
        assertTrue(schema.inputSchema().required().orElse(List.of()).contains("jql"));
    }

    @Test
    void xraySearchExecutions_hasRequiredJqlParam() {
        Tool schema = findTool("xray_search_executions");
        assertTrue(schema.inputSchema().required().orElse(List.of()).contains("jql"));
    }

    @Test
    void xrayGetTestExecution_hasRequiredIssueKeyParam() {
        Tool schema = findTool("xray_get_test_execution");
        assertTrue(schema.inputSchema().required().orElse(List.of()).contains("issueKey"));
    }

    @Test
    void xrayGetTestCoverage_hasRequiredIssueKeysParam() {
        Tool schema = findTool("xray_get_test_coverage");
        assertTrue(schema.inputSchema().required().orElse(List.of()).contains("issueKeys"));
    }

    @Test
    void xrayCreateTestExecution_hasRequiredParams() {
        Tool schema = findTool("xray_create_test_execution");
        List<String> required = schema.inputSchema().required().orElse(List.of());
        assertTrue(required.contains("projectKey"), "projectKey must be required");
        assertTrue(required.contains("summary"),    "summary must be required");
    }

    @Test
    void xrayUpdateTestRunStatus_hasRequiredParams() {
        Tool schema = findTool("xray_update_test_run_status");
        List<String> required = schema.inputSchema().required().orElse(List.of());
        assertTrue(required.contains("testRunId"), "testRunId must be required");
        assertTrue(required.contains("status"),    "status must be required");
    }

    // ─── Opt-in — NOT in default toolsets ─────────────────────────────────────────

    @Test
    void xrayTools_notPresentIn_chat_readOnly() {
        Set<String> chatNames = toolNames(ToolDefinitions.chat(false, false));
        assertTrue(chatNames.stream().noneMatch(EXPECTED_XRAY_TOOL_NAMES::contains),
                "Xray tools must not appear in chat(false,false)");
    }

    @Test
    void xrayTools_notPresentIn_chat_admin() {
        Set<String> chatNames = toolNames(ToolDefinitions.chat(true, true));
        assertTrue(chatNames.stream().noneMatch(EXPECTED_XRAY_TOOL_NAMES::contains),
                "Xray tools must not appear in chat(true,true)");
    }

    @Test
    void xrayTools_notPresentIn_planExecution() {
        Set<String> names = toolNames(ToolDefinitions.planExecution());
        assertTrue(names.stream().noneMatch(EXPECTED_XRAY_TOOL_NAMES::contains),
                "Xray tools must not appear in planExecution()");
    }

    @Test
    void xrayTools_notPresentIn_all() {
        Set<String> names = toolNames(ToolDefinitions.all());
        assertTrue(names.stream().noneMatch(EXPECTED_XRAY_TOOL_NAMES::contains),
                "Xray tools must not appear in all()");
    }

    @Test
    void xrayTools_notPresentIn_readOnly() {
        Set<String> names = toolNames(ToolDefinitions.readOnly());
        assertTrue(names.stream().noneMatch(EXPECTED_XRAY_TOOL_NAMES::contains),
                "Xray tools must not appear in readOnly()");
    }

    @Test
    void xrayTools_notPresentIn_docsGeneration() {
        Set<String> names = toolNames(ToolDefinitions.docsGeneration());
        assertTrue(names.stream().noneMatch(EXPECTED_XRAY_TOOL_NAMES::contains),
                "Xray tools must not appear in docsGeneration()");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private Set<String> toolNames(List<ToolUnion> tools) {
        return tools.stream()
                .filter(ToolUnion::isTool)
                .map(tu -> tu.asTool().name())
                .collect(Collectors.toSet());
    }

    private Tool findTool(String name) {
        return ToolDefinitions.xrayTools().stream()
                .filter(ToolUnion::isTool)
                .map(tu -> tu.asTool())
                .filter(t -> name.equals(t.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + name));
    }
}
