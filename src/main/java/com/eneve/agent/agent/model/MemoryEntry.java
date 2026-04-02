package com.eneve.agent.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A learned team preference or coding convention for a specific repository.
 * Stored in the {@code review_memory} table and injected into review prompts
 * so the agent remembers past feedback patterns.
 */
public record MemoryEntry(
        Long id,
        String workspace,
        String repoSlug,
        @JsonProperty("content") String memoryText,
        String category,
        String source,
        Long sourceCommentId,
        String sourcePrId,
        @JsonProperty("active") boolean isActive,
        Instant createdAt,
        String createdBy
) {

    public static MemoryEntry explicit(String workspace, String repoSlug,
                                       String memoryText, String createdBy) {
        return new MemoryEntry(null, workspace, repoSlug, memoryText,
                null, "EXPLICIT", null, null, true, Instant.now(), createdBy);
    }

    public static MemoryEntry extracted(String workspace, String repoSlug,
                                        String memoryText, String category,
                                        Long sourceCommentId, String sourcePrId,
                                        String createdBy) {
        return new MemoryEntry(null, workspace, repoSlug, memoryText,
                category, "EXTRACTED", sourceCommentId, sourcePrId,
                true, Instant.now(), createdBy);
    }
}
