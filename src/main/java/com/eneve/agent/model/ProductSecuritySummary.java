package com.eneve.agent.model;

import java.util.List;

/**
 * Security summary for a product, aggregating issues across all its repositories.
 */
public record ProductSecuritySummary(
        String productId,
        String displayName,
        List<RepoSecuritySummary> repos
) {}
