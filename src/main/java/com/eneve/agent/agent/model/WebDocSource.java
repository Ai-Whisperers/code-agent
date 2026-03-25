package com.eneve.agent.agent.model;

import java.time.Instant;

/**
 * Configuration for a single web documentation source to be crawled and indexed.
 * Stored in the {@code web_doc_sources} table.
 *
 * <p>Web docs are global, common knowledge — they are not scoped to a product or customer.
 */
public record WebDocSource(
        String id,
        String name,
        String baseUrl,
        String allowedPathPrefix,
        int maxPages,
        int crawlDelayMs,
        Instant lastCrawledAt,
        Integer lastCrawlChunks,
        String lastCrawlError,
        Instant createdAt
) {}
