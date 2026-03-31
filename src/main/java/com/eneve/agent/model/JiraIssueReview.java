package com.eneve.agent.model;

import java.time.Instant;

/**
 * Mirrors the {@code jira_issue_reviews} table.
 * Stores the result of an AI readiness review for a single Jira issue.
 */
public record JiraIssueReview(
        String id,
        String scopeId,
        String issueKey,
        String issueType,
        String issueSummary,
        String parentKey,
        String jiraStatus,
        Integer readinessScore,
        String readinessLabel,
        Integer complexityScore,
        String improvementSummary,
        String reviewJson,
        String jobId,
        Instant reviewedAt,
        Instant createdAt
) {}
