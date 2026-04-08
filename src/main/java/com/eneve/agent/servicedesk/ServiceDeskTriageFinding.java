package com.eneve.agent.servicedesk;

import java.time.Instant;
import java.util.List;

/**
 * Represents a single row in {@code service_desk_triage_findings}.
 */
public record ServiceDeskTriageFinding(
        long id,
        String issueKey,
        String projectKey,
        String category,
        String severity,
        Double confidence,
        String triageReason,
        String deepAnalysis,
        List<String> similarIssueKeys,
        Instant createdAt,
        Instant updatedAt
) {}
