package com.eneve.agent.agent.model;

import java.time.Instant;

public record AiCallRecord(
        Long id,
        String jobId,
        String jobType,
        String model,
        Integer iteration,
        long inputTokens,
        long outputTokens,
        long cacheCreationInputTokens,
        long cacheReadInputTokens,
        String stopReason,
        String toolNames,
        long durationMs,
        boolean isError,
        String errorMessage,
        Instant createdAt,
        String promptText,
        String responseText
) {}
