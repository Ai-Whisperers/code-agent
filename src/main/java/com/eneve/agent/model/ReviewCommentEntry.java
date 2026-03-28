package com.eneve.agent.model;

/**
 * A single inline review comment posted by the agent on a pull request.
 * Author is always "Bot"; timestamps are unavailable from the {@code getAgentPrComments()} API.
 */
public record ReviewCommentEntry(
        long commentId,
        String filePath,
        int line,
        String content,
        boolean resolved,
        /** ISO-8601 timestamp of when the finding was marked resolved, or null if still open. */
        String resolvedAt,
        /** Display name / username of whoever resolved the finding, or null if still open. */
        String resolvedBy
) {}
