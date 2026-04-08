package com.eneve.agent.mcp;

import com.eneve.agent.agent.model.QualityReport;
import com.eneve.agent.agent.store.QualityReportStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * MCP tool: Get quality report history for a repository branch.
 */
@ApplicationScoped
public class AgentGetQualityReportHistoryTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentGetQualityReportHistoryTool.class);

    @Inject
    QualityReportStore reportStore;

    @Override
    public String name() {
        return "agent_get_quality_report_history";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String workspaceName = (String) input.get("workspace");
        String repoSlug = (String) input.get("repoSlug");
        String branch = (String) input.get("branch");

        if (workspaceName == null || workspaceName.isBlank()) {
            return "ERROR: 'workspace' parameter is required";
        }
        if (repoSlug == null || repoSlug.isBlank()) {
            return "ERROR: 'repoSlug' parameter is required";
        }
        if (branch == null || branch.isBlank()) {
            return "ERROR: 'branch' parameter is required";
        }

        int limit = parseInt(input.get("limit"), 30);
        int safeLimit = Math.min(Math.max(1, limit), 100);

        try {
            List<QualityReport> history = reportStore.findHistory(workspaceName, repoSlug, branch, safeLimit);
            if (history.isEmpty()) {
                return "No quality report history found for " + workspaceName + "/" + repoSlug + "@" + branch;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Quality Report History (").append(history.size()).append(" reports):\n\n");
            for (QualityReport r : history) {
                sb.append("- ").append(r.measuredAt()).append(" | Score: ")
                        .append(String.format("%.2f%%", r.score() * 100));
                if (r.coverage() != null) {
                    sb.append(" | Coverage: ").append(String.format("%.1f%%", r.coverage().lineRate() * 100));
                }
                if (r.linter() != null) {
                    sb.append(" | Linter: ").append(r.linter().totalFindings()).append(" issues");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Failed to get quality report history: %s", e.getMessage());
            return "ERROR: Failed to get quality report history: " + e.getMessage();
        }
    }

    private int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
