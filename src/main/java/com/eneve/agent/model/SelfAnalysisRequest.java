package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Request payload for an autonomous self-analysis job")
public record SelfAnalysisRequest(

        @Schema(required = true, description = "Job ID of the failed job that triggered this analysis")
        String failedJobId,

        @Schema(required = true, description = "Repository URL of the code-agent repo to clone and fix")
        String repoUrl,

        @Schema(description = "Branch to check out as the base for the fix (default: develop)")
        String targetBranch,

        @Schema(description = "CustomerConfig customer ID used to resolve AWS credentials for CloudWatch")
        String customerId,

        @Schema(description = "EnvironmentConfig environment name to fetch CloudWatch logs from (e.g. production)")
        String environmentName,

        @Schema(description = "CloudWatch log group name to query (e.g. /ecs/code-agent)")
        String logGroupName,

        @Schema(description = "Jira project key for commit messages and PR description — optional, may be null")
        String jiraProjectKey

) implements JobPayload {

    public String targetBranchOrDefault() {
        return targetBranch != null && !targetBranch.isBlank() ? targetBranch : "develop";
    }
}
