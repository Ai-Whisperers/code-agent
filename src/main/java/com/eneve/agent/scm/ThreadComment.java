package com.eneve.agent.scm;

/**
 * Represents a single comment within a pull request comment thread.
 *
 * @param id        platform comment ID
 * @param parentId  parent comment ID (0 if this is the root)
 * @param author    display name of the comment author
 * @param content   raw Markdown content
 * @param createdOn ISO-8601 timestamp
 * @param isAgent   true if the comment was posted by the agent user
 */
public record ThreadComment(
        long id,
        long parentId,
        String author,
        String content,
        String createdOn,
        boolean isAgent
) {}
