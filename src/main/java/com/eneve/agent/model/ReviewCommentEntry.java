package com.eneve.agent.model;

/**
 * A single inline review comment posted by the agent on a pull request.
 * Author is always "Bot"; timestamps are unavailable from the {@code getAgentPrComments()} API.
 */
public record ReviewCommentEntry(
        String filePath,
        int line,
        String content
) {}
