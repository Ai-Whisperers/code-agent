package com.eneve.agent.mcp;

import com.eneve.agent.agent.model.QualityReport;
import com.eneve.agent.agent.store.QualityReportStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * MCP tool: Compare quality reports across multiple branches.
 */
@ApplicationScoped
public class AgentCompareQualityReportsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentCompareQualityReportsTool.class);

    @Inject
    QualityReportStore reportStore;

    @Override
    public String name() {
        return "agent_compare_quality_reports";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String workspaceName = (String) input.get("workspace");
        String repoSlug = (String) input.get("repoSlug");

        if (workspaceName == null || workspaceName.isBlank()) {
            return "ERROR: 'workspace' parameter is required";
        }
        if (repoSlug == null || repoSlug.isBlank()) {
            return "ERROR: 'repoSlug' parameter is required";
        }

        String branchesParam = (String) input.get("branches");
        if (branchesParam == null || branchesParam.isBlank()) {
            branchesParam = "main,develop";
        }

        String[] requestedBranches = branchesParam.split(",");

        try {
            Map<String, QualityReport> latestPerBranch = reportStore.findLatestPerBranch(workspaceName, repoSlug);

            StringBuilder sb = new StringBuilder();
            sb.append("Quality Report Comparison for ").append(workspaceName).append("/").append(repoSlug).append("\n\n");

            for (String b : requestedBranches) {
                String trimmed = b.trim();
                QualityReport r = latestPerBranch.get(trimmed);
                if (r != null) {
                    sb.append(trimmed).append(":\n");
                    sb.append("  Score: ").append(String.format("%.2f%%", r.score() * 100)).append("\n");
                    if (r.coverage() != null) {
                        sb.append("  Coverage: ").append(String.format("%.1f%%", r.coverage().lineRate() * 100)).append("\n");
                    }
                    if (r.linter() != null) {
                        sb.append("  Linter: ").append(r.linter().totalFindings()).append(" issues\n");
                    }
                    if (r.aikido() != null) {
                        sb.append("  Security: ").append(r.aikido().totalIssues()).append(" issues\n");
                    }
                } else {
                    sb.append(trimmed).append(": No report found\n");
                }
                sb.append("\n");
            }

            // Compute deltas if exactly 2 branches requested
            if (requestedBranches.length == 2) {
                String branchA = requestedBranches[0].trim();
                String branchB = requestedBranches[1].trim();
                QualityReport a = latestPerBranch.get(branchA);
                QualityReport b = latestPerBranch.get(branchB);
                if (a != null && b != null) {
                    sb.append("Deltas (").append(branchB).append(" vs ").append(branchA).append("):\n");
                    sb.append("  Score: ").append(formatDelta(b.score() - a.score())).append("\n");
                    if (a.coverage() != null && b.coverage() != null) {
                        double covDelta = (b.coverage().lineRate() - a.coverage().lineRate()) * 100;
                        sb.append("  Coverage: ").append(formatDelta(covDelta / 100)).append("\n");
                    }
                    if (a.linter() != null && b.linter() != null) {
                        int lintDelta = b.linter().totalFindings() - a.linter().totalFindings();
                        sb.append("  Linter issues: ").append(lintDelta > 0 ? "+" : "").append(lintDelta).append("\n");
                    }
                }
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Failed to compare quality reports: %s", e.getMessage());
            return "ERROR: Failed to compare quality reports: " + e.getMessage();
        }
    }

    private String formatDelta(double delta) {
        double percentage = delta * 100;
        if (percentage > 0) {
            return "+" + String.format("%.2f%%", percentage);
        } else if (percentage < 0) {
            return String.format("%.2f%%", percentage);
        }
        return "0.00%";
    }
}
