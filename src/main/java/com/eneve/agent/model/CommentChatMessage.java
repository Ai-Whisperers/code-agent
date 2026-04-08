package com.eneve.agent.model;

/**
 * A single message turn in a comment chat conversation.
 * Role is either "user" or "assistant".
 */
public record CommentChatMessage(String role, String content) {}
