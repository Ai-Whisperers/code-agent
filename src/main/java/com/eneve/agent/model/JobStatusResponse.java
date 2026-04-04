package com.eneve.agent.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

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

        @Schema(description = "URL of the created pull request")
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
        String targetBranch,

        @Schema(description = "Before/after coverage snapshots captured during a GENERATE_TESTS job")
        JobCoverageData coverageData,

        @Schema(description = "Bitbucket/GitLab workspace or organisation that owns the repository")
        String workspace,

        @Schema(description = "Repository slug within the workspace")
        String repoSlug,

        // ── SOC II / SLA fields ───────────────────────────────────────────────

        @Schema(description = "Pull request identifier (numeric or slug, platform-specific)")
        String prId,

        @Schema(description = "Jira issue type cached at submission time, e.g. Bug, Task")
        String jiraIssueType,

        @Schema(description = "Jira priority cached at submission time, e.g. Critical, High")
        String jiraPriority,

        @Schema(description = "Timestamp when the Jira ticket was created (SLA clock start)")
        Instant jiraCreatedAt,

        @Schema(description = "SLA deadline derived from Jira creation date + configured SLA days for this priority")
        Instant slaDeadline,

        @Schema(description = "SLA status: ON_TRACK, AT_RISK, OVERDUE, MET, MISSED, NOT_APPLICABLE")
        String slaStatus,

        @Schema(description = "Optional Aikido vulnerability issue ID linked to this job")
        String aikidoIssueId,

        @Schema(description = "True when this job is SOC II-applicable (Bug-type Jira ticket on a protected branch)")
        boolean soc2Protected,

        @Schema(description = "Scytale evidence reference ID, set after successful SOC II evidence upload")
        String scytaleEvidenceRef,

        @Schema(description = "True when Scytale integration is configured in system settings")
        boolean scytaleEnabled,

        @Schema(description = "Job ID of the SOC2 promotion (cherry-pick to main) job, if one has been created")
        String promotionJobId
) {
    public static JobStatusResponse from(JobRecord record, int queuePosition,
                                         int criticalDays, int highDays,
                                         List<String> bugIssueTypes,
                                         boolean scytaleEnabled) {
        String sourceBranch = null;
        String targetBranch = null;
        String jiraKey = null;

        if (record.getRequest() != null) {
            sourceBranch = record.getRequest().branchName();
            targetBranch = record.getRequest().targetBranchOrDefault();
            jiraKey = record.getRequest().jiraKey();
        } else if (record.getGenerateTestsRequest() != null) {
            sourceBranch = record.getGenerateTestsRequest().branchName();
            targetBranch = record.getGenerateTestsRequest().targetBranchOrDefault();
        } else if (record.getGenerateDocsRequest() != null) {
            sourceBranch = record.getGenerateDocsRequest().branchName();
            targetBranch = record.getGenerateDocsRequest().targetBranchOrDefault();
        } else if (record.getHookRequest() != null) {
            sourceBranch = record.getHookRequest().branchName();
            targetBranch = record.getHookRequest().targetBranch();
        } else if (record.getReviewRequest() != null) {
            jiraKey = record.getReviewRequest().jiraKey();
        } else if (record.getFixPrRequest() != null) {
            jiraKey = record.getFixPrRequest().jiraKey();
        }

        // SLA computation
        Instant slaDeadline = null;
        String slaStatus = "NOT_APPLICABLE";
        String priority = record.getJiraPriority();
        Instant jiraCreatedAt = record.getJiraCreatedAt();

        if (priority != null && jiraCreatedAt != null) {
            int slaDays = 0;
            if ("Critical".equalsIgnoreCase(priority)) {
                slaDays = criticalDays;
            } else if ("High".equalsIgnoreCase(priority)) {
                slaDays = highDays;
            }
            if (slaDays > 0) {
                slaDeadline = jiraCreatedAt.plusSeconds((long) slaDays * 86400);
                Instant now = Instant.now();
                long secondsLeft = slaDeadline.getEpochSecond() - now.getEpochSecond();
                boolean merged = record.getStatus() == JobStatus.SUCCESS;

                if (merged) {
                    slaStatus = secondsLeft >= 0 ? "MET" : "MISSED";
                } else if (secondsLeft < 0) {
                    slaStatus = "OVERDUE";
                } else if (secondsLeft <= 2L * 86400) {
                    slaStatus = "AT_RISK";
                } else {
                    slaStatus = "ON_TRACK";
                }
            }
        }

        // SOC II protection flag
        boolean soc2Protected = record.getJiraIssueType() != null
                && bugIssueTypes.stream().anyMatch(t -> t.equalsIgnoreCase(record.getJiraIssueType()));

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
                jiraKey,
                sourceBranch,
                targetBranch,
                record.getCoverageData(),
                record.getWorkspace(),
                record.getRepoSlug(),
                record.getPrId(),
                record.getJiraIssueType(),
                record.getJiraPriority(),
                jiraCreatedAt,
                slaDeadline,
                slaStatus,
                record.getAikidoIssueId(),
                soc2Protected,
                record.getScytaleEvidenceRef(),
                scytaleEnabled,
                record.getPromotionJobId()
        );
    }

    /** Backward-compatible overload used by the search listing (no SLA computation needed). */
    public static JobStatusResponse fromSearch(String jobId, JobType jobType, JobStatus status,
                                               Instant createdAt, String summary, String errorMessage,
                                               String prUrl, int filesChanged, int linesChanged,
                                               int priority, String jiraKey,
                                               String workspace, String repoSlug) {
        return new JobStatusResponse(
                jobId, jobType, status, createdAt, summary, errorMessage,
                prUrl, filesChanged, linesChanged, 0, priority, jiraKey,
                null, null, null,
                workspace, repoSlug,
                null, null, null, null, null, "NOT_APPLICABLE",
                null, false, null, false, null
        );
    }
}
