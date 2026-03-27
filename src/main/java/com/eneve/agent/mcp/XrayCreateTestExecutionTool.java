package com.eneve.agent.mcp;

import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.xray.XrayService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MCP tool: Create a new Xray test execution.
 */
@ApplicationScoped
public class XrayCreateTestExecutionTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(XrayCreateTestExecutionTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    XrayService xrayService;

    @Override
    public String name() {
        return "xray_create_test_execution";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String projectKey = (String) input.get("projectKey");
        String summary    = (String) input.get("summary");

        if (projectKey == null || projectKey.isBlank()) {
            return "ERROR: 'projectKey' parameter is required (e.g. 'PROJ')";
        }
        if (summary == null || summary.isBlank()) {
            return "ERROR: 'summary' parameter is required";
        }

        List<String> testIssueIds = List.of();
        Object idsParam = input.get("testIssueIds");
        if (idsParam instanceof List<?> rawList) {
            testIssueIds = rawList.stream().map(Object::toString).collect(Collectors.toList());
        } else if (idsParam instanceof String s && !s.isBlank()) {
            testIssueIds = Arrays.stream(s.split("[,\\s]+"))
                    .map(String::trim)
                    .filter(k -> !k.isBlank())
                    .collect(Collectors.toList());
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
            String createdKey = xrayService.createTestExecution(projectKey, summary, testIssueIds, creds.get());
            if (createdKey == null) {
                return "ERROR: Failed to create test execution — check project key and test IDs";
            }
            return "Created test execution: " + createdKey
                    + (testIssueIds.isEmpty() ? "" : " with " + testIssueIds.size() + " test(s)");
        } catch (Exception e) {
            LOG.errorf("Xray create test execution failed: %s", e.getMessage());
            return "ERROR: Failed to create test execution: " + e.getMessage();
        }
    }
}
