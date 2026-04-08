package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request payload for Jira readiness review jobs (REVIEW_EPIC, REVIEW_FEATURE, REVIEW_USERSTORY).
 *
 * <p>{@code scopeId} is nullable — when null the review is a standalone (hook-triggered)
 * review not associated with any scope, and scope-scoped override checks are skipped.
 */
@Schema(description = "Jira issue readiness review request")
public record JiraReviewRequest(
        String scopeId,
        String issueKey,
        String issueType,
        String parentKey,
        String grandparentKey
) implements JobPayload {
    public JiraReviewRequest(String scopeId, String issueKey, String issueType) {
        this(scopeId, issueKey, issueType, null, null);
    }
}
