package com.eneve.agent.model;

import java.time.Instant;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Current status and details of a code fix job")
public record JobStatusResponse(

        @Schema(description = "Unique job identifier", example = "550e8400-e29b-41d4-a716-446655440000")
        String jobId,

        @Schema(description = "Current job status", enumeration = {"PENDING", "RUNNING", "SUCCESS", "FAILED", "AWAITING_APPROVAL"})
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
        int linesChanged
) {
    public static JobStatusResponse from(JobRecord record) {
        return new JobStatusResponse(
                record.getJobId(),
                record.getStatus(),
                record.getCreatedAt(),
                record.getSummary(),
                record.getErrorMessage(),
                record.getPrUrl(),
                record.getFilesChanged(),
                record.getLinesChanged()
        );
    }
}
