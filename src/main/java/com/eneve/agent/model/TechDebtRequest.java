package com.eneve.agent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload for a {@link JobType#TECH_DEBT} job.
 *
 * @param productId    The product to analyse. {@code null} means all configured products.
 * @param lookbackDays How far back in git history to look when sourcing knowledge-graph scores
 *                     (default 365 days). The handler will prefer the most recent knowledge-graph
 *                     snapshot whose {@code lookback_days} is &ge; this value.
 */
public record TechDebtRequest(
        @JsonProperty("productId")   String productId,
        @JsonProperty("lookbackDays") int    lookbackDays
) implements JobPayload {

    @JsonCreator
    public TechDebtRequest {
        if (lookbackDays <= 0) lookbackDays = 365;
    }

    /** Convenience constructor: analyse all products with default lookback. */
    public TechDebtRequest() {
        this(null, 365);
    }
}
