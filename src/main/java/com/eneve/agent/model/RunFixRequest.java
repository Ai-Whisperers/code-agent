package com.eneve.agent.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Request to submit a new code fix job")
public record RunFixRequest(

        @Schema(required = true, description = "Repository URL (HTTPS or SSH, Bitbucket or Azure DevOps)",
                example = "https://bitbucket.org/workspace/repo.git")
        String repoUrl,

        @Schema(required = true, description = "Branch name to create or push to",
                example = "agent/PROJ-123-upgrade-log4j")
        String branchName,

        @Schema(required = true, description = "JIRA issue key for tracking",
                example = "PROJ-123")
        String jiraKey,

        @Schema(description = "Task prompt describing what the agent should fix. If empty, the JIRA issue summary and description will be used.",
                example = "Upgrade log4j from 2.19.0 to 2.23.1 in this Maven project")
        String prompt,

        @Schema(description = "Target branch for the PR (default: main)",
                example = "main")
        String targetBranch,

        @Schema(description = "n8n webhook URL for job completion callback",
                example = "https://n8n.example.com/webhook/abc")
        String n8nWebhookUrl,

        @Schema(description = "URL of a shared Cursor rules repo to load coding standards from",
                example = "https://bitbucket.org/workspace/cursor-rules.git")
        String rulesRepoUrl,

        @Schema(description = "List of rule names to load from the shared rules repo",
                example = "[\"java-conventions\", \"maven-standards\"]")
        List<String> ruleNames,

        @Schema(description = "Inline extra rules to append to the system prompt",
                example = "Do not modify test files")
        String extraRules
) {
    public String targetBranchOrDefault() {
        return targetBranch != null && !targetBranch.isBlank() ? targetBranch : "main";
    }
}
