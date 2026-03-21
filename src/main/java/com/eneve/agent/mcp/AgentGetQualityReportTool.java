package com.eneve.agent.mcp;

import java.util.Map;
import java.util.Optional;

import com.eneve.agent.agent.QualityReport;
import com.eneve.agent.agent.QualityReportStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * MCP tool: Get the latest quality report for a repository branch.
 */
@ApplicationScoped
public class AgentGetQualityReportTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentGetQualityReportTool.class);

    @Inject
    QualityReportStore reportStore;

    @Override
    public String name() {
        return "agent_get_quality_report";
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

        try {
            Optional<QualityReport> reportOpt = reportStore.findLatest(workspaceName, repoSlug, branch);
            if (reportOpt.isEmpty()) {
                return "No quality report found for " + workspaceName + "/" + repoSlug + "@" + branch;
            }

            QualityReport r = reportOpt.get();
            StringBuilder sb = new StringBuilder();
            sb.append("Quality Report for ").append(workspaceName).append("/").append(repoSlug).append("@").append(branch).append("\n");
            sb.append("Score: ").append(String.format("%.2f", r.score() * 100)).append("%\n");
            sb.append("Measured at: ").append(r.measuredAt()).append("\n\n");

            if (r.coverage() != null) {
                sb.append("Coverage:\n");
                sb.append("  - Line rate: ").append(String.format("%.2f%%", r.coverage().lineRate() * 100)).append("\n");
                sb.append("  - Branch rate: ").append(String.format("%.2f%%", r.coverage().branchRate() * 100)).append("\n");
            }

            if (r.linter() != null) {
                sb.append("Linter:\n");
                sb.append("  - Total findings: ").append(r.linter().totalFindings()).append("\n");
                sb.append("  - Errors: ").append(r.linter().errorCount()).append("\n");
            }

            if (r.aikido() != null) {
                sb.append("Security (Aikido):\n");
                sb.append("  - Total issues: ").append(r.aikido().totalIssues()).append("\n");
                sb.append("  - Critical: ").append(r.aikido().criticalCount()).append("\n");
            }

            if (r.complexity() != null) {
                sb.append("Complexity:\n");
                sb.append("  - Average: ").append(String.format("%.2f", r.complexity().avgComplexity())).append("\n");
                sb.append("  - Methods above threshold: ").append(r.complexity().methodsAboveThreshold()).append("\n");
            }

            if (r.reviewQuality() != null) {
                sb.append("Review Quality:\n");
                sb.append("  - Resolution rate: ").append(String.format("%.2f%%", r.reviewQuality().resolutionRate() * 100)).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Failed to get quality report: %s", e.getMessage());
            return "ERROR: Failed to get quality report: " + e.getMessage();
        }
    }
}
