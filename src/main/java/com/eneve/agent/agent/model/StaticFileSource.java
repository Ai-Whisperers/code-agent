package com.eneve.agent.agent.model;

import java.time.Instant;

/**
 * An admin-uploaded static file that has been indexed into the knowledge base.
 * Stored in the {@code static_file_sources} table; the actual bytes live in S3
 * under {@link #s3Key()}.
 *
 * <p>Static files are global, common knowledge — they are not scoped to a product
 * or customer.  The corresponding source type in {@code knowledge_embeddings} is
 * {@code static-file}.
 */
public record StaticFileSource(
        String id,
        String name,
        String originalFilename,
        String contentType,
        String s3Key,
        long fileSize,
        Instant indexedAt,
        Integer chunkCount,
        String indexError,
        Instant createdAt
) {}
