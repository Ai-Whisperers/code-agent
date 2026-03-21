package com.eneve.agent.mcp;

import java.util.Map;

import com.eneve.agent.agent.CommentFeedbackStore;
import com.eneve.agent.agent.CommentStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * MCP tool: Get review quality metrics for a repository.
 */
@ApplicationScoped
public class AgentGetReviewMetricsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentGetReviewMetricsTool.class);

    @Inject
    CommentStore commentStore;

    @Inject
    CommentFeedbackStore feedbackStore;

    @ConfigProperty(name = "review.fp.auto-suppress-threshold", defaultValue = "3")
    int fpAutoSuppressThreshold;

    @Override
    public String name() {
        return "agent_get_review_metrics";
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

        try {
            long totalFindings = commentStore.countTotalFindings(workspaceName, repoSlug);
            long resolvedFindings = commentStore.countResolvedFindings(workspaceName, repoSlug);
            long falsePositives = feedbackStore.countFalsePositives(workspaceName, repoSlug);
            Map<String, Long> fpByCategory = feedbackStore.countFalsePositivesByCategory(workspaceName, repoSlug);
            int autoSuppressedPatterns = feedbackStore.findRecurringPatterns(workspaceName, repoSlug, fpAutoSuppressThreshold).size();

            double fpRate = totalFindings > 0
                    ? Math.round((double) falsePositives / totalFindings * 10000.0) / 10000.0
                    : 0.0;
            double resolutionRate = totalFindings > 0
                    ? Math.round((double) resolvedFindings / totalFindings * 10000.0) / 10000.0
                    : 0.0;

            StringBuilder sb = new StringBuilder();
            sb.append("Review Metrics for ").append(workspaceName).append("/").append(repoSlug).append(":\n\n");
            sb.append("Total Findings: ").append(totalFindings).append("\n");
            sb.append("Resolved by Developer: ").append(resolvedFindings).append("\n");
            sb.append("Resolution Rate: ").append(String.format("%.2f%%", resolutionRate * 100)).append("\n");
            sb.append("False Positives: ").append(falsePositives).append("\n");
            sb.append("FP Rate: ").append(String.format("%.2f%%", fpRate * 100)).append("\n");
            sb.append("Auto-Suppressed Patterns: ").append(autoSuppressedPatterns).append("\n");

            if (!fpByCategory.isEmpty()) {
                sb.append("\nFalse Positives by Category:\n");
                fpByCategory.forEach((category, count) ->
                        sb.append("  - ").append(category).append(": ").append(count).append("\n"));
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Failed to get review metrics: %s", e.getMessage());
            return "ERROR: Failed to get review metrics: " + e.getMessage();
        }
    }
}
