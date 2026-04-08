package com.eneve.agent.model;

/**
 * A single commit that belongs to a pull request.
 */
public record PrCommitEntry(
        String sha,
        String shortSha,
        String message,
        String authorName,
        String authorDate
) {}
