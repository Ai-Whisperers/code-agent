package com.eneve.agent.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Request to submit a unit test generation job")
public record GenerateTestsRequest(

        @Schema(required = true, description = "Repository URL (HTTPS or SSH, Bitbucket or Azure DevOps)",
                example = "https://bitbucket.org/workspace/repo.git")
        String repoUrl,

        @Schema(required = true, description = "Branch name to create or push to",
                example = "agent/tests/PROJ-123-add-unit-tests")
        String branchName,

        @Schema(description = "Target branch for the PR (default: develop)",
                example = "develop")
        String targetBranch,

        @Schema(description = "Specific source files or packages to generate tests for. "
                + "If empty, the agent will scan src/main/java for classes lacking coverage.",
                example = "[\"src/main/java/com/example/UserService.java\", \"src/main/java/com/example/order/\"]")
        List<String> targetFiles,

        @Schema(description = "JIRA issue key for tracking (optional)",
                example = "PROJ-123")
        String jiraKey,

        @Schema(description = "n8n webhook URL for job completion callback",
                example = "https://n8n.example.com/webhook/abc")
        String n8nWebhookUrl,

        @Schema(description = "URL of a shared Cursor rules repo to load coding standards from",
                example = "https://bitbucket.org/workspace/cursor-rules.git")
        String rulesRepoUrl,

        @Schema(description = "List of rule names to load from the shared rules repo",
                example = "[\"java-conventions\", \"test-standards\"]")
        List<String> ruleNames,

        @Schema(description = "Inline extra instructions to append to the system prompt",
                example = "Prefer AssertJ assertions over plain JUnit assertions")
        String extraRules
) implements JobPayload {
    public String targetBranchOrDefault() {
        return targetBranch != null && !targetBranch.isBlank() ? targetBranch : "develop";
    }
}
