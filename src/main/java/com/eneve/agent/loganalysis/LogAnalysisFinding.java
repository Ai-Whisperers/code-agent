package com.eneve.agent.loganalysis;

import java.time.Instant;

/**
 * Represents a single deduplicated log finding row from {@code log_analysis_findings}.
 */
public record LogAnalysisFinding(
        long id,
        String fingerprint,
        String customerId,
        String environmentName,
        String logGroupName,
        String exceptionClass,
        String topFrames,
        String sampleMessage,
        Instant firstSeenAt,
        Instant lastSeenAt,
        int occurrenceCount,
        Instant suppressUntil,
        String aiDecision,
        String severity,
        String aiReason,
        String status
) {}
