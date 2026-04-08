package com.eneve.agent.mcp;

import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.xray.XrayService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP tool: Search Xray test cases using JQL.
 */
@ApplicationScoped
public class XraySearchTestsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(XraySearchTestsTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    XrayService xrayService;

    @Override
    public String name() {
        return "xray_search_tests";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String jql = (String) input.get("jql");
        if (jql == null || jql.isBlank()) {
            return "ERROR: 'jql' parameter is required (e.g. 'project = PROJ AND labels = regression')";
        }

        int maxResults = 10;
        Object maxObj = input.get("maxResults");
        if (maxObj instanceof Number n) {
            maxResults = Math.min(Math.max(1, n.intValue()), 50);
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
            List<XrayService.XrayTest> tests = xrayService.searchTests(jql, maxResults, creds.get());
            if (tests.isEmpty()) {
                return "No test cases found for JQL: " + jql;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(tests.size()).append(" test case(s):\n\n");
            for (XrayService.XrayTest test : tests) {
                sb.append("- ").append(test.key()).append(": ").append(test.summary()).append("\n");
                if (!test.status().isBlank()) {
                    sb.append("  Status: ").append(test.status()).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Xray search tests failed: %s", e.getMessage());
            return "ERROR: Failed to search Xray tests: " + e.getMessage();
        }
    }
}
