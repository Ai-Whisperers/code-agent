package com.eneve.agent.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Request to submit a pull request review job")
public record ReviewPrRequest(

        @Schema(required = true, description = "Bitbucket Cloud repo URL (HTTPS or SSH)",
                example = "https://bitbucket.org/workspace/repo.git")
        String repoUrl,

        @Schema(required = true, description = "Bitbucket PR number to review",
                example = "42")
        String prId,

        @Schema(description = "Target branch the PR merges into (resolved from Bitbucket if omitted)",
                example = "main")
        String targetBranch,

        @Schema(description = "JIRA issue key for tracking (optional)",
                example = "PROJ-123")
        String jiraKey,

        @Schema(description = "URL of a shared Cursor rules repo to load coding standards from",
                example = "https://bitbucket.org/workspace/cursor-rules.git")
        String rulesRepoUrl,

        @Schema(description = "List of rule names to load from the shared rules repo",
                example = "[\"java-conventions\", \"maven-standards\"]")
        List<String> ruleNames,

        @Schema(description = "Inline extra review instructions to append to the system prompt",
                example = "Pay special attention to thread safety in concurrent code")
        String extraRules,

        @Schema(description = "n8n webhook URL for job completion callback",
                example = "https://n8n.example.com/webhook/abc")
        String n8nWebhookUrl
) {
}
