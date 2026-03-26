package com.eneve.agent.model;

import java.time.Instant;

/**
 * Mirrors the {@code roadmap_items} table row.
 * Populated during the Jira sync step; AI review results are stored
 * separately in {@code jira_issue_reviews}.
 */
public record RoadmapItem(
        String id,
        String roadmapId,
        String issueKey,
        String issueType,
        String parentKey,
        String grandparentKey,
        String summary,
        String jiraStatus,
        Instant syncedAt,
        /** Jira {@code updated} timestamp. Null when not yet fetched or unavailable. */
        Instant jiraModifiedAt,
        String assignee,
        String reporter,
        String sprintName,
        Instant sprintStart,
        Instant sprintEnd
) {}
