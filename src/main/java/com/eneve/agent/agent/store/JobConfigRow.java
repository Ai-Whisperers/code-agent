package com.eneve.agent.agent.store;

import java.time.Instant;

public record JobConfigRow(
        String jobType,
        String modelTier,
        boolean thinkingEnabled,
        Integer thinkingBudget,
        Integer maxOutputTokens,
        Instant updatedAt
) {}
