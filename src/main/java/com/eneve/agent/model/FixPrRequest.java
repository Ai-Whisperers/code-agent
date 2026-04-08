package com.eneve.agent.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Request to auto-fix a pull request based on its review comments")
public record FixPrRequest(

        @Schema(required = true, description = "Repository URL (HTTPS or SSH, Bitbucket or Azure DevOps)",
                example = "https://bitbucket.org/workspace/repo.git")
        String repoUrl,

        @Schema(required = true, description = "Pull request number whose review comments should be fixed",
                example = "42")
        String prId,

        @Schema(description = "JIRA issue key for tracking (optional)",
                example = "PROJ-123")
        String jiraKey,

        @Schema(description = "URL of a shared Cursor rules repo to load coding standards from",
                example = "https://bitbucket.org/workspace/cursor-rules.git")
        String rulesRepoUrl,

        @Schema(description = "List of rule names to load from the shared rules repo",
                example = "[\"java-conventions\", \"maven-standards\"]")
        List<String> ruleNames,

        @Schema(description = "Inline extra instructions to append to the system prompt",
                example = "Do not modify test files")
        String extraRules,

        @Schema(description = "n8n webhook URL for job completion callback",
                example = "https://n8n.example.com/webhook/abc")
        String n8nWebhookUrl
) implements JobPayload {
}
