package com.eneve.agent.model;

public record RunResult(
        String jobId,
        String jobType,
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
