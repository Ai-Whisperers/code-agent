package com.eneve.agent.mcp;

import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.xray.XrayService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * MCP tool: Get full details of a single Xray test execution including all test run results.
 */
@ApplicationScoped
public class XrayGetTestExecutionTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(XrayGetTestExecutionTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    XrayService xrayService;

    @Override
    public String name() {
        return "xray_get_test_execution";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String issueKey = (String) input.get("issueKey");
        if (issueKey == null || issueKey.isBlank()) {
            return "ERROR: 'issueKey' parameter is required (e.g. 'PROJ-456')";
        }

        String userId = workspace != null ? workspace.getUserId() : null;
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return "ERROR: No authenticated user. Please link your Xray account in Settings > MCP Profiles.";
        }

        Optional<XrayService.XrayCredentials> creds = linkedAccountService.resolveXray(userId);
        if (creds.isEmpty()) {
            return "ERROR: No Xray account linked. Please link your Xray Cloud account in Settings > MCP Profiles.";
        }

        try {
            XrayService.XrayTestExecution execution = xrayService.getTestExecution(issueKey, creds.get());
            if (execution == null) {
                return "No test execution found for key: " + issueKey;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Test Execution: ").append(execution.key()).append("\n");
            sb.append("Summary: ").append(execution.summary()).append("\n");
            sb.append("Test Runs: ").append(execution.testRuns().size()).append("\n\n");

            if (execution.testRuns().isEmpty()) {
                sb.append("No test runs in this execution.\n");
            } else {
                for (XrayService.XrayTestRun run : execution.testRuns()) {
                    sb.append("- [").append(run.status()).append("] ");
                    if (!run.testKey().isBlank()) {
                        sb.append(run.testKey()).append(": ");
                    }
                    sb.append(run.testSummary()).append("\n");
                    if (!run.startedOn().isBlank()) {
                        sb.append("  Started: ").append(run.startedOn()).append("\n");
                    }
                    if (!run.finishedOn().isBlank()) {
                        sb.append("  Finished: ").append(run.finishedOn()).append("\n");
                    }
                    sb.append("  Run ID: ").append(run.id()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Xray get test execution failed for %s: %s", issueKey, e.getMessage());
            return "ERROR: Failed to fetch test execution " + issueKey + ": " + e.getMessage();
        }
    }
}
