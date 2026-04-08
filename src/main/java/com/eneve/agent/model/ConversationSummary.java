package com.eneve.agent.model;

import java.time.Instant;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Lightweight summary of a chat conversation returned by {@code GET /conversations}.
 * Does not include the full message history — use {@code POST /chat} with the
 * {@code conversationId} to resume and retrieve content.
 */
@Schema(description = "Summary of a stored chat conversation")
public record ConversationSummary(

        @Schema(description = "Unique conversation identifier")
        String conversationId,

        @Schema(description = "Auto-generated title derived from the first message")
        String title,

        @Schema(description = "Product scope this conversation was started in, if any")
        String productId,

        @Schema(description = "When the conversation was first created")
        Instant createdAt,

        @Schema(description = "When the last message was appended")
        Instant updatedAt,

        @Schema(description = "Total number of stored messages (user + assistant turns)")
        int messageCount
) {}
