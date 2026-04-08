package com.eneve.agent.agent.model;

/**
 * Captures the review context of a comment posted by the agent on a pull request.
 * Stored in PostgreSQL so the agent can respond to developer replies in-thread.
 */
public record CommentContext(
        String prId,
        String organization,
        String project,
        String repository,
        String filePath,
        int line,
        String category,
        String severity,
        String findingText,
        String reviewJobId
) {}
