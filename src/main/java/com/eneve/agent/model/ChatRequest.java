package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Freeform chat request to the AI assistant")
public record ChatRequest(
        @Schema(required = true, description = "The user's question or message",
                example = "Which team member is responsible for the payment service?")
        String message,

        @Schema(description = "Optional product ID to scope the assistant's knowledge",
                example = "myproduct-platform")
        String productId,

        @Schema(description = "Optional conversation ID for future multi-turn support")
        String conversationId
) {}
