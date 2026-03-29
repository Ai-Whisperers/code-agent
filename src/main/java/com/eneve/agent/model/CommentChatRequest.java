package com.eneve.agent.model;

import java.util.List;

/**
 * Request body for the comment-chat SSE endpoint.
 * The full conversation history is sent from the client on every turn (stateless backend).
 */
public record CommentChatRequest(
        long commentId,
        List<CommentChatMessage> messages
) {}
