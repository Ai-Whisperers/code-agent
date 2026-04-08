package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Conversation context containing references to customers, products, issues, and documents")
public record ConversationContext(
        @Schema(required = true, description = "Conversation ID this context belongs to")
        String conversationId,

        @Schema(description = "Customer configuration IDs attached to this conversation")
        List<String> customerIds,

        @Schema(description = "Product configuration IDs attached to this conversation") 
        List<String> productIds,

        @Schema(description = "Aikido issue group IDs attached to this conversation")
        List<Integer> aikidoIssueIds,

        @Schema(description = "Jira issue keys attached to this conversation")
        List<String> jiraIssueKeys,

        @Schema(description = "Confluence document IDs attached to this conversation")
        List<String> confluenceDocIds,

        @Schema(readOnly = true, description = "When this context was created")
        Instant createdAt,

        @Schema(readOnly = true, description = "When this context was last updated")
        Instant updatedAt
) {
    public ConversationContext withUpdatedAt(Instant updatedAt) {
        return new ConversationContext(
            conversationId, customerIds, productIds, aikidoIssueIds, 
            jiraIssueKeys, confluenceDocIds, createdAt, updatedAt
        );
    }
}
