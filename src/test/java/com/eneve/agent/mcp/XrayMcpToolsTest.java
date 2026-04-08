package com.eneve.agent.mcp;

import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.xray.XrayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the six Xray MCP {@link com.eneve.agent.tools.ToolExecutor} implementations.
 *
 * <p>All external dependencies ({@link LinkedAccountService}, {@link XrayService})
 * are mocked. No Quarkus container or real HTTP calls are made.
 */
class XrayMcpToolsTest {

    private static final String USER_ID = "user-abc";
    private static final XrayService.XrayCredentials CREDS =
            new XrayService.XrayCredentials("clientId", "secret", "https://xray.cloud.getxray.app");

    private LinkedAccountService linkedAccountService;
    private XrayService xrayService;
    private WorkspaceContext workspace;

    @BeforeEach
    void setUpCommon() {
        linkedAccountService = Mockito.mock(LinkedAccountService.class);
        xrayService          = Mockito.mock(XrayService.class);
        workspace            = Mockito.mock(WorkspaceContext.class);
        when(workspace.getUserId()).thenReturn(USER_ID);
        when(linkedAccountService.resolveXray(USER_ID)).thenReturn(Optional.of(CREDS));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // XraySearchTestsTool
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    class SearchTestsToolTests {

        private XraySearchTestsTool tool;

        @BeforeEach
        void setUp() throws Exception {
            tool = buildTool(XraySearchTestsTool.class);
        }

        @Test
        void name_returnsCorrectId() {
            assertEquals("xray_search_tests", tool.name());
        }

        @Test
        void isReadOnly_returnsTrue() {
            assertTrue(tool.isReadOnly());
        }

        @Test
        void missingJql_returnsError() {
            String result = tool.execute(workspace, Map.of());
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("'jql'"));
        }

        @Test
        void blankJql_returnsError() {
            String result = tool.execute(workspace, Map.of("jql", "   "));
            assertTrue(result.startsWith("ERROR:"));
        }

        @Test
        void nullWorkspace_returnsAuthError() {
            String result = tool.execute(null, Map.of("jql", "project = PROJ"));
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("authenticated user"));
        }

        @Test
        void anonymousUser_returnsAuthError() {
            when(workspace.getUserId()).thenReturn("anonymous");
            String result = tool.execute(workspace, Map.of("jql", "project = PROJ"));
            assertTrue(result.startsWith("ERROR:"));
        }

        @Test
        void noLinkedAccount_returnsLinkError() {
            when(linkedAccountService.resolveXray(USER_ID)).thenReturn(Optional.empty());
            String result = tool.execute(workspace, Map.of("jql", "project = PROJ"));
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("Xray"));
        }

        @Test
        void noResults_returnsNoResultsMessage() {
            when(xrayService.searchTests(anyString(), anyInt(), any())).thenReturn(List.of());
            String result = tool.execute(workspace, Map.of("jql", "project = PROJ"));
            assertTrue(result.contains("No test cases found"));
        }

        @Test
        void withResults_returnsFormattedList() {
            when(xrayService.searchTests(anyString(), anyInt(), any())).thenReturn(List.of(
                    new XrayService.XrayTest("1", "PROJ-1", "Login test", "TODO"),
                    new XrayService.XrayTest("2", "PROJ-2", "Logout test", "PASS")
            ));
            String result = tool.execute(workspace, Map.of("jql", "project = PROJ"));
            assertTrue(result.contains("PROJ-1"));
            assertTrue(result.contains("Login test"));
            assertTrue(result.contains("PROJ-2"));
            assertTrue(result.contains("2 test case(s)"));
        }

        @Test
        void maxResultsClamped_to50() {
            when(xrayService.searchTests(anyString(), eq(50), any())).thenReturn(List.of());
            tool.execute(workspace, Map.of("jql", "project = PROJ", "maxResults", 999));
            Mockito.verify(xrayService).searchTests(anyString(), eq(50), any());
        }

        @Test
        void serviceThrowsException_returnsError() {
            when(xrayService.searchTests(anyString(), anyInt(), any()))
                    .thenThrow(new RuntimeException("network failure"));
            String result = tool.execute(workspace, Map.of("jql", "project = PROJ"));
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("network failure"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // XraySearchExecutionsTool
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    class SearchExecutionsToolTests {

        private XraySearchExecutionsTool tool;

        @BeforeEach
        void setUp() throws Exception {
            tool = buildTool(XraySearchExecutionsTool.class);
        }

        @Test
        void name_returnsCorrectId() {
            assertEquals("xray_search_executions", tool.name());
        }

        @Test
        void isReadOnly_returnsTrue() {
            assertTrue(tool.isReadOnly());
        }

        @Test
        void missingJql_returnsError() {
            String result = tool.execute(workspace, Map.of());
            assertTrue(result.startsWith("ERROR:"));
        }

        @Test
        void noResults_returnsMessage() {
            when(xrayService.searchTestExecutions(anyString(), anyInt(), any())).thenReturn(List.of());
            String result = tool.execute(workspace, Map.of("jql", "project = PROJ"));
            assertTrue(result.contains("No test executions found"));
        }

        @Test
        void withResults_returnsFormattedList() {
            when(xrayService.searchTestExecutions(anyString(), anyInt(), any())).thenReturn(List.of(
                    new XrayService.XrayTest("10", "EX-1", "Sprint 1 run", "DONE")
            ));
            String result = tool.execute(workspace, Map.of("jql", "project = PROJ"));
            assertTrue(result.contains("EX-1"));
            assertTrue(result.contains("Sprint 1 run"));
            assertTrue(result.contains("1 test execution(s)"));
        }

        @Test
        void serviceThrowsException_returnsError() {
            when(xrayService.searchTestExecutions(anyString(), anyInt(), any()))
                    .thenThrow(new RuntimeException("timeout"));
            String result = tool.execute(workspace, Map.of("jql", "project = PROJ"));
            assertTrue(result.startsWith("ERROR:"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // XrayGetTestExecutionTool
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    class GetTestExecutionToolTests {

        private XrayGetTestExecutionTool tool;

        @BeforeEach
        void setUp() throws Exception {
            tool = buildTool(XrayGetTestExecutionTool.class);
        }

        @Test
        void name_returnsCorrectId() {
            assertEquals("xray_get_test_execution", tool.name());
        }

        @Test
        void isReadOnly_returnsTrue() {
            assertTrue(tool.isReadOnly());
        }

        @Test
        void missingIssueKey_returnsError() {
            String result = tool.execute(workspace, Map.of());
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("'issueKey'"));
        }

        @Test
        void executionNotFound_returnsNotFoundMessage() {
            when(xrayService.getTestExecution(eq("EX-99"), any())).thenReturn(null);
            String result = tool.execute(workspace, Map.of("issueKey", "EX-99"));
            assertTrue(result.contains("No test execution found"));
        }

        @Test
        void executionFound_noRuns_returnsHeader() {
            XrayService.XrayTestExecution exec =
                    new XrayService.XrayTestExecution("1", "EX-1", "Sprint run", List.of());
            when(xrayService.getTestExecution(eq("EX-1"), any())).thenReturn(exec);

            String result = tool.execute(workspace, Map.of("issueKey", "EX-1"));
            assertTrue(result.contains("EX-1"));
            assertTrue(result.contains("Sprint run"));
            assertTrue(result.contains("No test runs"));
        }

        @Test
        void executionFound_withRuns_listsRunStatuses() {
            List<XrayService.XrayTestRun> runs = List.of(
                    new XrayService.XrayTestRun("r1", "T-1", "Login test", "PASS", "", ""),
                    new XrayService.XrayTestRun("r2", "T-2", "Logout test", "FAIL", "", "")
            );
            XrayService.XrayTestExecution exec =
                    new XrayService.XrayTestExecution("1", "EX-1", "Sprint run", runs);
            when(xrayService.getTestExecution(eq("EX-1"), any())).thenReturn(exec);

            String result = tool.execute(workspace, Map.of("issueKey", "EX-1"));
            assertTrue(result.contains("PASS"));
            assertTrue(result.contains("FAIL"));
            assertTrue(result.contains("Login test"));
            assertTrue(result.contains("r1"));
        }

        @Test
        void serviceThrowsException_returnsError() {
            when(xrayService.getTestExecution(anyString(), any()))
                    .thenThrow(new RuntimeException("connection refused"));
            String result = tool.execute(workspace, Map.of("issueKey", "EX-1"));
            assertTrue(result.startsWith("ERROR:"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // XrayGetTestCoverageTool
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    class GetTestCoverageToolTests {

        private XrayGetTestCoverageTool tool;

        @BeforeEach
        void setUp() throws Exception {
            tool = buildTool(XrayGetTestCoverageTool.class);
        }

        @Test
        void name_returnsCorrectId() {
            assertEquals("xray_get_test_coverage", tool.name());
        }

        @Test
        void isReadOnly_returnsTrue() {
            assertTrue(tool.isReadOnly());
        }

        @Test
        void missingIssueKeys_returnsError() {
            String result = tool.execute(workspace, Map.of());
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("'issueKeys'"));
        }

        @Test
        void emptyIssueKeysList_returnsError() {
            String result = tool.execute(workspace, Map.of("issueKeys", List.of()));
            assertTrue(result.startsWith("ERROR:"));
        }

        @Test
        void tooManyKeys_returnsError() {
            List<String> tooMany = java.util.stream.IntStream.rangeClosed(1, 21)
                    .mapToObj(i -> "PROJ-" + i).toList();
            String result = tool.execute(workspace, Map.of("issueKeys", tooMany));
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("Maximum 20"));
        }

        @Test
        void commaSeparatedStringKeys_areParsed() {
            when(xrayService.getTestCoverage(anyList(), any())).thenReturn(Map.of());
            tool.execute(workspace, Map.of("issueKeys", "PROJ-1, PROJ-2"));
            Mockito.verify(xrayService).getTestCoverage(
                    argThat(list -> list.size() == 2 && list.contains("PROJ-1") && list.contains("PROJ-2")),
                    any());
        }

        @Test
        void coverageResult_showsCoveredAndUncovered() {
            XrayService.XrayTest t = new XrayService.XrayTest("1", "T-1", "Some test", "PASS");
            when(xrayService.getTestCoverage(anyList(), any())).thenReturn(Map.of(
                    "PROJ-1", List.of(t),
                    "PROJ-2", List.of()
            ));
            String result = tool.execute(workspace,
                    Map.of("issueKeys", List.of("PROJ-1", "PROJ-2")));

            assertTrue(result.contains("1 covered"));
            assertTrue(result.contains("1 not covered"));
            assertTrue(result.contains("PROJ-1"));
            assertTrue(result.contains("PROJ-2"));
            assertTrue(result.contains("NO COVERAGE"));
        }

        @Test
        void serviceThrowsException_returnsError() {
            when(xrayService.getTestCoverage(anyList(), any()))
                    .thenThrow(new RuntimeException("GraphQL error"));
            String result = tool.execute(workspace, Map.of("issueKeys", List.of("PROJ-1")));
            assertTrue(result.startsWith("ERROR:"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // XrayCreateTestExecutionTool
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    class CreateTestExecutionToolTests {

        private XrayCreateTestExecutionTool tool;

        @BeforeEach
        void setUp() throws Exception {
            tool = buildTool(XrayCreateTestExecutionTool.class);
        }

        @Test
        void name_returnsCorrectId() {
            assertEquals("xray_create_test_execution", tool.name());
        }

        @Test
        void isReadOnly_returnsFalse() {
            assertFalse(tool.isReadOnly());
        }

        @Test
        void missingProjectKey_returnsError() {
            String result = tool.execute(workspace, Map.of("summary", "Sprint run"));
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("'projectKey'"));
        }

        @Test
        void missingSummary_returnsError() {
            String result = tool.execute(workspace, Map.of("projectKey", "PROJ"));
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("'summary'"));
        }

        @Test
        void serviceReturnsNull_returnsError() {
            when(xrayService.createTestExecution(anyString(), anyString(), anyList(), any()))
                    .thenReturn(null);
            String result = tool.execute(workspace,
                    Map.of("projectKey", "PROJ", "summary", "Sprint 1 tests"));
            assertTrue(result.startsWith("ERROR:"));
        }

        @Test
        void successfulCreation_returnsCreatedKey() {
            when(xrayService.createTestExecution(eq("PROJ"), eq("Sprint 1 tests"), anyList(), any()))
                    .thenReturn("EX-42");
            String result = tool.execute(workspace,
                    Map.of("projectKey", "PROJ", "summary", "Sprint 1 tests"));
            assertTrue(result.contains("EX-42"));
        }

        @Test
        void withTestIds_passedToService() {
            when(xrayService.createTestExecution(anyString(), anyString(), anyList(), any()))
                    .thenReturn("EX-5");
            List<String> ids = List.of("10001", "10002");
            String result = tool.execute(workspace,
                    Map.of("projectKey", "PROJ", "summary", "Run", "testIssueIds", ids));

            assertTrue(result.contains("EX-5"));
            assertTrue(result.contains("2 test(s)"));
            Mockito.verify(xrayService).createTestExecution(eq("PROJ"), eq("Run"), eq(ids), any());
        }

        @Test
        void commaSeparatedTestIds_areParsed() {
            when(xrayService.createTestExecution(anyString(), anyString(), anyList(), any()))
                    .thenReturn("EX-6");
            tool.execute(workspace,
                    Map.of("projectKey", "PROJ", "summary", "Run", "testIssueIds", "10001, 10002"));
            Mockito.verify(xrayService).createTestExecution(
                    anyString(), anyString(),
                    argThat(list -> list.size() == 2),
                    any());
        }

        @Test
        void serviceThrowsException_returnsError() {
            when(xrayService.createTestExecution(anyString(), anyString(), anyList(), any()))
                    .thenThrow(new RuntimeException("mutation failed"));
            String result = tool.execute(workspace,
                    Map.of("projectKey", "PROJ", "summary", "Sprint 1 tests"));
            assertTrue(result.startsWith("ERROR:"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // XrayUpdateTestRunStatusTool
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    class UpdateTestRunStatusToolTests {

        private XrayUpdateTestRunStatusTool tool;

        @BeforeEach
        void setUp() throws Exception {
            tool = buildTool(XrayUpdateTestRunStatusTool.class);
        }

        @Test
        void name_returnsCorrectId() {
            assertEquals("xray_update_test_run_status", tool.name());
        }

        @Test
        void isReadOnly_returnsFalse() {
            assertFalse(tool.isReadOnly());
        }

        @Test
        void missingTestRunId_returnsError() {
            String result = tool.execute(workspace, Map.of("status", "PASS"));
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("'testRunId'"));
        }

        @Test
        void missingStatus_returnsError() {
            String result = tool.execute(workspace, Map.of("testRunId", "run-1"));
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("'status'"));
        }

        @Test
        void invalidStatus_returnsError() {
            String result = tool.execute(workspace,
                    Map.of("testRunId", "run-1", "status", "FLYING"));
            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("Invalid status"));
        }

        @Test
        void statusIsCaseInsensitive() {
            when(xrayService.updateTestRunStatus(eq("run-1"), eq("PASS"), any())).thenReturn(true);
            String result = tool.execute(workspace,
                    Map.of("testRunId", "run-1", "status", "pass"));
            assertFalse(result.startsWith("ERROR:"));
            assertTrue(result.contains("PASS"));
        }

        @Test
        void allValidStatuses_areAccepted() {
            for (String s : List.of("TODO", "EXECUTING", "PASS", "FAIL", "ABORTED", "BLOCKED")) {
                when(xrayService.updateTestRunStatus(anyString(), eq(s), any())).thenReturn(true);
                String result = tool.execute(workspace,
                        Map.of("testRunId", "run-1", "status", s));
                assertFalse(result.startsWith("ERROR:"), "Status " + s + " should be accepted");
            }
        }

        @Test
        void serviceReturnsFalse_returnsError() {
            when(xrayService.updateTestRunStatus(anyString(), anyString(), any())).thenReturn(false);
            String result = tool.execute(workspace,
                    Map.of("testRunId", "run-1", "status", "PASS"));
            assertTrue(result.startsWith("ERROR:"));
        }

        @Test
        void serviceReturnsTrue_returnsSuccessMessage() {
            when(xrayService.updateTestRunStatus(eq("run-1"), eq("FAIL"), any())).thenReturn(true);
            String result = tool.execute(workspace,
                    Map.of("testRunId", "run-1", "status", "FAIL"));
            assertTrue(result.contains("run-1"));
            assertTrue(result.contains("FAIL"));
        }

        @Test
        void serviceThrowsException_returnsError() {
            when(xrayService.updateTestRunStatus(anyString(), anyString(), any()))
                    .thenThrow(new RuntimeException("mutation error"));
            String result = tool.execute(workspace,
                    Map.of("testRunId", "run-1", "status", "PASS"));
            assertTrue(result.startsWith("ERROR:"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Shared auth / credential checks (applied to all tools)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void allTools_noLinkedAccount_returnErrorWithXrayMention() throws Exception {
        when(linkedAccountService.resolveXray(USER_ID)).thenReturn(Optional.empty());

        Object[] tools = {
            buildTool(XraySearchTestsTool.class),
            buildTool(XraySearchExecutionsTool.class),
            buildTool(XrayGetTestExecutionTool.class),
            buildTool(XrayGetTestCoverageTool.class),
            buildTool(XrayCreateTestExecutionTool.class),
            buildTool(XrayUpdateTestRunStatusTool.class)
        };

        Map<Object, Map<String, Object>> inputs = Map.of(
            tools[0], Map.of("jql", "project = PROJ"),
            tools[1], Map.of("jql", "project = PROJ"),
            tools[2], Map.of("issueKey", "EX-1"),
            tools[3], Map.of("issueKeys", List.of("PROJ-1")),
            tools[4], Map.of("projectKey", "P", "summary", "s"),
            tools[5], Map.of("testRunId", "r1", "status", "PASS")
        );

        for (Object tool : tools) {
            var toolExecutor = (com.eneve.agent.tools.ToolExecutor) tool;
            String result = toolExecutor.execute(workspace, inputs.get(tool));
            assertTrue(result.startsWith("ERROR:"),
                    toolExecutor.name() + " must return ERROR when no credentials");
            assertTrue(result.contains("Xray"),
                    toolExecutor.name() + " error must mention Xray");
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private <T> T buildTool(Class<T> clazz) throws Exception {
        T tool = clazz.getDeclaredConstructor().newInstance();
        injectField(tool, "linkedAccountService", linkedAccountService);
        injectField(tool, "xrayService",          xrayService);
        return tool;
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), name);
            }
            throw e;
        }
    }
}
