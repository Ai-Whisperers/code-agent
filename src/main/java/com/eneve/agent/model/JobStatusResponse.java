package com.eneve.agent.model;

import java.time.Instant;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Current status and details of a code fix job")
public record JobStatusResponse(

        @Schema(description = "Unique job identifier", example = "550e8400-e29b-41d4-a716-446655440000")
        String jobId,

        @Schema(description = "Type of the job", enumeration = {"FIX", "REVIEW", "FIX_PR", "REPLY", "FIX_COMMENT", "HOOK", "GENERATE_TESTS", "GENERATE_DOCS", "SYNC_CONFLUENCE", "METRICS", "QUALITY_REPORT", "REVIEW_EPIC", "REVIEW_FEATURE", "REVIEW_USERSTORY"})
        JobType jobType,

        @Schema(description = "Current job status", enumeration = {"PENDING", "QUEUED", "RUNNING", "SUCCESS", "FAILED", "AWAITING_APPROVAL"})
        JobStatus status,

        @Schema(description = "Timestamp when the job was created")
        Instant createdAt,

        @Schema(description = "AI-generated summary of the changes made")
        String summary,

        @Schema(description = "Error message if the job failed")
        String errorMessage,

        @Schema(description = "URL of the created pull request in Bitbucket Cloud")
        String prUrl,

        @Schema(description = "Number of files changed by the agent")
        int filesChanged,

        @Schema(description = "Number of lines changed by the agent")
        int linesChanged,

        @Schema(description = "Position in the execution queue (1-based). 0 if not queued (running or completed).")
        int queuePosition,

        @Schema(description = "Dispatch priority (1-100, higher = dispatched first)")
        int priority,

        @Schema(description = "Jira issue key associated with this job, if applicable")
        String jiraKey,

        @Schema(description = "Source branch of the pull request (feature branch)")
        String sourceBranch,

        @Schema(description = "Target branch the pull request will merge into")
        String targetBranch
) {
    public static JobStatusResponse from(JobRecord record, int queuePosition) {
        String sourceBranch = null;
        String targetBranch = null;

        if (record.getRequest() != null) {
            sourceBranch = record.getRequest().branchName();
            targetBranch = record.getRequest().targetBranchOrDefault();
        } else if (record.getGenerateTestsRequest() != null) {
            sourceBranch = record.getGenerateTestsRequest().branchName();
            targetBranch = record.getGenerateTestsRequest().targetBranchOrDefault();
        } else if (record.getGenerateDocsRequest() != null) {
            sourceBranch = record.getGenerateDocsRequest().branchName();
            targetBranch = record.getGenerateDocsRequest().targetBranchOrDefault();
        } else if (record.getHookRequest() != null) {
            sourceBranch = record.getHookRequest().branchName();
            targetBranch = record.getHookRequest().targetBranch();
        }

        return new JobStatusResponse(
                record.getJobId(),
                record.getJobType(),
                record.getStatus(),
                record.getCreatedAt(),
                record.getSummary(),
                record.getErrorMessage(),
                record.getPrUrl(),
                record.getFilesChanged(),
                record.getLinesChanged(),
                queuePosition,
                record.getPriority(),
                null,
                sourceBranch,
                targetBranch
        );
    }
}
