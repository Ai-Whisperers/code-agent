package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request parameters for a METRICS job.
 * A METRICS job clones the repository, runs the cyclomatic complexity calculator,
 * persists the snapshot, and returns the result as the job summary.
 */
@Schema(description = "Request to calculate cyclomatic complexity metrics for a repository")
public record MetricsJobRequest(

        @Schema(required = true, description = "Repository URL (HTTPS)",
                example = "https://bitbucket.org/workspace/repo.git")
        String repoUrl,

        @Schema(required = true, description = "Branch to analyse",
                example = "main")
        String branch,

        @Schema(description = "Bitbucket / Azure DevOps workspace name",
                example = "my-workspace")
        String workspace,

        @Schema(description = "Repository slug (short name)",
                example = "my-repo")
        String repoSlug,

        @Schema(description = "Cyclomatic complexity threshold — methods with CC above this "
                + "value are flagged as violators. Default: 10",
                example = "10")
        int ccThreshold,

        @Schema(description = "Maximum number of fix iterations in the quality improvement loop. "
                + "Ignored when this is a standalone metrics job. Default: 3",
                example = "3")
        int maxIterations,

        @Schema(description = "Plan ID this metrics step belongs to (populated by the orchestrator; "
                + "leave null for standalone jobs)",
                example = "plan-abc123")
        String planId
) {
    public int effectiveThreshold() {
        return ccThreshold > 0 ? ccThreshold : 10;
    }

    public int effectiveMaxIterations() {
        return maxIterations > 0 ? maxIterations : 3;
    }
}
