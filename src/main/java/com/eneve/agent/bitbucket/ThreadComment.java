package com.eneve.agent.bitbucket;

/**
 * Represents a single comment within a Bitbucket PR comment thread.
 *
 * @param id        Bitbucket comment ID
 * @param parentId  parent comment ID (0 if this is the root)
 * @param author    display name of the comment author
 * @param content   raw Markdown content
 * @param createdOn ISO-8601 timestamp from Bitbucket
 * @param isAgent   true if the comment was posted by the agent's Bitbucket user
 */
public record ThreadComment(
        long id,
        long parentId,
        String author,
        String content,
        String createdOn,
        boolean isAgent
) {}
