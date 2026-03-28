package com.eneve.agent.model;

import java.time.Instant;
import java.util.List;

/**
 * Response for {@code GET /jobs/{jobId}/evidence}.
 *
 * <p>Compliance checks are derived from immutable audit log events — not from
 * mutable {@code job.status} fields — so the evidence cannot be silently
 * invalidated by a status reset.
 */
public record JobEvidenceResponse(
        String jobId,
        JobType jobType,
        String prUrl,
        String sourceBranch,
        String targetBranch,
        Instant createdAt,
        Instant completedAt,
        String jiraKey,
        String jiraIssueType,
        String reviewJobId,
        String reviewJobStatus,
        String promotionJobId,
        boolean complianceApplicable,
        List<ComplianceCheck> complianceChecks,
        List<EvidenceEntry> auditTrail,
        String scytaleEvidenceRef,
        boolean scytaleEnabled
) {
    /**
     * A single pass/fail compliance control.
     */
    public record ComplianceCheck(String name, boolean passed, String detail) {}
}
