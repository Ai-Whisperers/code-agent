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
 * MCP tool: Get test coverage for a set of Jira issue keys.
 * Returns which stories/features have linked Xray test cases.
 */
@ApplicationScoped
public class XrayGetTestCoverageTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(XrayGetTestCoverageTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    XrayService xrayService;

    @Override
    public String name() {
        return "xray_get_test_coverage";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        List<String> issueKeys = null;

        Object keysParam = input.get("issueKeys");
        if (keysParam instanceof List<?> rawList) {
            issueKeys = rawList.stream().map(Object::toString).collect(Collectors.toList());
        } else if (keysParam instanceof String s) {
            issueKeys = Arrays.stream(s.split("[,\\s]+"))
                    .map(String::trim)
                    .filter(k -> !k.isBlank())
                    .collect(Collectors.toList());
        }

        if (issueKeys == null || issueKeys.isEmpty()) {
            return "ERROR: 'issueKeys' parameter is required (comma-separated list or array of Jira issue keys)";
        }

        if (issueKeys.size() > 20) {
            return "ERROR: Maximum 20 issue keys per request. Provided: " + issueKeys.size();
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
            Map<String, List<XrayService.XrayTest>> coverage =
                    xrayService.getTestCoverage(issueKeys, creds.get());

            long covered   = coverage.values().stream().filter(tests -> !tests.isEmpty()).count();
            long uncovered = coverage.values().stream().filter(List::isEmpty).count();

            StringBuilder sb = new StringBuilder();
            sb.append("Test coverage for ").append(issueKeys.size()).append(" issue(s): ")
              .append(covered).append(" covered, ").append(uncovered).append(" not covered.\n\n");

            for (Map.Entry<String, List<XrayService.XrayTest>> entry : coverage.entrySet()) {
                String key = entry.getKey();
                List<XrayService.XrayTest> tests = entry.getValue();
                if (tests.isEmpty()) {
                    sb.append("- ").append(key).append(": NO COVERAGE\n");
                } else {
                    sb.append("- ").append(key).append(": ").append(tests.size()).append(" test(s)\n");
                    for (XrayService.XrayTest t : tests) {
                        sb.append("    • ").append(t.key()).append(": ").append(t.summary());
                        if (!t.status().isBlank()) {
                            sb.append(" [").append(t.status()).append("]");
                        }
                        sb.append("\n");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Xray get test coverage failed: %s", e.getMessage());
            return "ERROR: Failed to fetch test coverage: " + e.getMessage();
        }
    }
}
