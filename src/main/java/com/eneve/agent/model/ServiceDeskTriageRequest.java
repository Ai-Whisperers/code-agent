package com.eneve.agent.model;

/**
 * Request payload for {@link JobType#SERVICE_DESK_TRIAGE} jobs.
 *
 * <p>Carries the Jira issue fields captured at webhook time so the handler
 * does not need to re-fetch the issue from the Jira API.
 */
public record ServiceDeskTriageRequest(
        String issueKey,
        String projectKey,
        String summary,
        String description,
        String issueType,
        String priority
) implements JobPayload {}
