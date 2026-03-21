package com.eneve.agent.model;

/**
 * Immutable result record for a completed (or initiated) agent job.
 *
 * <p>The {@code status} field carries one of three values:
 * <ul>
 *   <li>{@code "SUCCESS"} – job finished successfully</li>
 *   <li>{@code "FAILED"}  – job finished with an error</li>
 *   <li>{@code "STARTED"} – job was created and started but has not yet completed
 *       (used by the upgrade scheduler notification)</li>
 * </ul>
 */
public record RunResult(
        String jobId,
        String jobType,
        String status,
        String jiraKey,
        String repoUrl,
        String branchName,
        String prUrl,
        String summary,
        String errorMessage,
        int filesChanged,
        int linesChanged
) {
    /** Convenience: returns {@code true} when {@code status} is {@code "SUCCESS"}. */
    public boolean success() {
        return "SUCCESS".equals(status);
    }
}
