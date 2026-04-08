package com.eneve.agent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload for a {@link JobType#KNOWLEDGE_GRAPH} job.
 *
 * @param productId   The product to analyse. {@code null} means all configured products.
 * @param lookbackDays How far back in git history to look (default 365 days).
 */
public record KnowledgeGraphRequest(
        @JsonProperty("productId")   String productId,
        @JsonProperty("lookbackDays") int    lookbackDays
) implements JobPayload {

    @JsonCreator
    public KnowledgeGraphRequest {
        if (lookbackDays <= 0) lookbackDays = 365;
    }

    /** Convenience constructor: analyse all products with default lookback. */
    public KnowledgeGraphRequest() {
        this(null, 365);
    }
}
