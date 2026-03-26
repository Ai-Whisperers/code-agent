package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request payload for Jira readiness review jobs (REVIEW_EPIC, REVIEW_FEATURE, REVIEW_USERSTORY).
 *
 * <p>{@code roadmapId} is nullable — when null the review is a standalone (hook-triggered)
 * review not associated with any roadmap, and roadmap-scoped override checks are skipped.
 */
@Schema(description = "Jira issue readiness review request")
public record JiraReviewRequest(
        String roadmapId,
        String issueKey,
        String issueType,
        String parentKey,
        String grandparentKey
) {
    public JiraReviewRequest(String roadmapId, String issueKey, String issueType) {
        this(roadmapId, issueKey, issueType, null, null);
    }
}
