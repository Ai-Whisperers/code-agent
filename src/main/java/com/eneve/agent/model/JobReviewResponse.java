package com.eneve.agent.model;

import java.time.Instant;
import java.util.List;

/**
 * Response for {@code GET /jobs/{jobId}/review}.
 *
 * {@code reviewJobStatus} reflects the live status of the linked REVIEW job:
 * {@code RUNNING}, {@code PENDING}, {@code SUCCESS}, {@code FAILED}, or {@code null}
 * when no REVIEW job exists for this PR.
 */
public record JobReviewResponse(
        String reviewJobId,
        String reviewJobStatus,
        String reviewSummary,
        Instant reviewedAt,
        List<ReviewCommentEntry> comments
) {}
