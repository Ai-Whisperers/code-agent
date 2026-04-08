package com.eneve.agent.model;

import java.time.Instant;

/**
 * Lightweight projection used by the {@code GET /compliance/soc2} audit screen.
 *
 * {@code reviewStatus} is one of: {@code NONE}, {@code IN_PROGRESS}, {@code COMPLETE}.
 */
public record Soc2JobSummary(
        String jobId,
        JobType jobType,
        String jiraKey,
        String jiraPriority,
        String aikidoIssueId,
        String slaStatus,
        Instant slaDeadline,
        String reviewStatus,
        JobStatus jobStatus,
        String prUrl,
        boolean scytaleUploaded,
        Instant createdAt
) {}
