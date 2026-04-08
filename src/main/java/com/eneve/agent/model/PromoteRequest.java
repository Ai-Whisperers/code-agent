package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request payload for a PROMOTE job.
 * The handler creates promote/{jiraKey} from main, cherry-picks the commits
 * from the original fix branch, and raises a PR → main for SOC2 review.
 */
@Schema(description = "Request to promote a security fix from develop to main via cherry-pick")
public record PromoteRequest(

        @Schema(required = true, description = "Repository clone URL")
        String repoUrl,

        @Schema(required = true, description = "JIRA issue key linked to the original fix")
        String jiraKey,

        @Schema(required = true, description = "The original agent fix branch (e.g. agent/PROJ-123-fix). Commits are cherry-picked from here.")
        String fixBranchName,

        @Schema(description = "PR ID of the merged develop PR, used to retrieve commit SHAs via SCM API")
        String originalPrId,

        @Schema(description = "Target production branch (defaults to 'main')")
        String targetBranch,

        @Schema(description = "Optional Aikido issue group ID for audit correlation")
        String aikidoIssueId
) implements JobPayload {
    public String targetBranchOrDefault() {
        return targetBranch != null && !targetBranch.isBlank() ? targetBranch : "main";
    }

    public String promoteBranchName() {
        return "promote/" + jiraKey;
    }
}
