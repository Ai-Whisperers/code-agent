package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request parameters for a QUALITY_REPORT job.
 * A QUALITY_REPORT job clones the repository, runs all available quality measurements
 * (coverage, linting, security, complexity, review quality), and persists the resulting
 * {@link com.eneve.agent.agent.QualityReport} with an aggregate score.
 */
@Schema(description = "Request to collect a quality report snapshot for a repository branch")
public record QualityReportJobRequest(

        @Schema(required = true, description = "Repository URL (HTTPS)",
                example = "https://bitbucket.org/workspace/repo.git")
        String repoUrl,

        @Schema(required = true, description = "Branch to measure",
                example = "main")
        String branch,

        @Schema(description = "Workspace / organisation name",
                example = "my-workspace")
        String workspace,

        @Schema(description = "Repository slug (short name)",
                example = "my-repo")
        String repoSlug
) {}
