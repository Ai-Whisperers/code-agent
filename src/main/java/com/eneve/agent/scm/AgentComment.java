package com.eneve.agent.scm;

/**
 * Represents an existing comment posted by the agent on a pull request.
 * Used for deduplication during incremental reviews.
 *
 * @param filePath relative file path (empty for general comments)
 * @param line     line number on the new side (0 for general or file-level comments)
 * @param content  raw Markdown content of the comment
 */
public record AgentComment(String filePath, int line, String content) {}
