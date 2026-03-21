package com.eneve.agent.agent;

/**
 * Represents an unresolved inline finding posted by the agent on a previous review pass.
 * Used by the resolution check to determine whether the developer has addressed the issue.
 */
public record OpenFinding(
        long commentId,
        String filePath,
        int line,
        String findingText,
        String severity
) {}
