package com.eneve.agent.model;

import java.time.Instant;

/**
 * A single Aikido vulnerability issue enriched with SLA and linked job data.
 *
 * <p>{@code slaStatus} is one of: {@code ON_TRACK}, {@code AT_RISK}, {@code OVERDUE}.
 * <p>{@code issueType} is one of: {@code sca}, {@code sast}, {@code container}, {@code secrets}, {@code unknown}.
 * <p>{@code linkedJobId} and {@code linkedJobStatus} are {@code null} when no fix job exists yet.
 */
public record SecurityIssueRow(
        int issueGroupId,
        String issueType,
        String title,
        String severity,
        String packageName,
        String currentVersion,
        String fixedVersion,
        String cveId,
        Double cvssScore,
        String repoName,
        String repoUrl,
        String containerImage,
        Instant createdAt,
        Instant slaDeadline,
        String slaStatus,
        String linkedJobId,
        String linkedJobStatus
) {}
