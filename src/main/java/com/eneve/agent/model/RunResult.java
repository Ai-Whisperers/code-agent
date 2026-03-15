package com.eneve.agent.model;

public record RunResult(
        String jobId,
        boolean success,
        String jiraKey,
        String repoUrl,
        String branchName,
        String prUrl,
        String summary,
        String errorMessage,
        int filesChanged,
        int linesChanged
) {
}
