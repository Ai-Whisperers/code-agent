package com.eneve.agent.model;

import java.time.Instant;

/**
 * Mirrors the {@code roadmaps} table.
 *
 * <p>The three {@code *Issuetype} fields store the Jira issue-type names used
 * when syncing this roadmap. They default to the global settings values but can
 * be overridden per-roadmap to support projects with non-standard naming
 * conventions (e.g. "User Story" instead of "Story").
 */
public record RoadmapRecord(
        String id,
        String name,
        String label,
        String epicIssuetype,
        String featureIssuetype,
        String userstoryIssuetype,
        Instant createdAt
) {}
