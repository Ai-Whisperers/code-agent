package com.eneve.agent.mcp;

import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.xray.XrayService;
import com.eneve.agent.xray.XrayTestRunStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * MCP tool: Update the status of a single test run within an Xray test execution.
 */
@ApplicationScoped
public class XrayUpdateTestRunStatusTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(XrayUpdateTestRunStatusTool.class);

    private static final java.util.Set<String> VALID_STATUSES = XrayTestRunStatus.NAMES;

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    XrayService xrayService;

    @Override
    public String name() {
        return "xray_update_test_run_status";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override public boolean isDestructive() { return true; }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String testRunId = (String) input.get("testRunId");
        String status    = (String) input.get("status");

        if (testRunId == null || testRunId.isBlank()) {
            return "ERROR: 'testRunId' parameter is required (obtain from xray_get_test_execution)";
        }
        if (status == null || status.isBlank()) {
            return "ERROR: 'status' parameter is required. Valid values: " + VALID_STATUSES;
        }

        String normalizedStatus = status.toUpperCase();
        if (!VALID_STATUSES.contains(normalizedStatus)) {
            return "ERROR: Invalid status '" + status + "'. Valid values: " + VALID_STATUSES;
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
            boolean updated = xrayService.updateTestRunStatus(testRunId, normalizedStatus, creds.get());
            if (!updated) {
                return "ERROR: Failed to update test run status — check the test run ID and status value";
            }
            return "Updated test run " + testRunId + " status to " + normalizedStatus;
        } catch (Exception e) {
            LOG.errorf("Xray update test run status failed for runId=%s: %s", testRunId, e.getMessage());
            return "ERROR: Failed to update test run status: " + e.getMessage();
        }
    }
}
