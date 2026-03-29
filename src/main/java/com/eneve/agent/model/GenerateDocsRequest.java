package com.eneve.agent.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to generate documentation for a repository.
 * The agent explores the codebase and produces markdown docs in the docs/ folder,
 * optionally publishing them to Confluence.
 */
@Schema(description = "Documentation generation job request")
public record GenerateDocsRequest(
        @Schema(description = "Git clone URL (HTTPS or SSH) of the repository", required = true)
        String repoUrl,

        @Schema(description = "Branch to generate docs on (created from targetBranch if it doesn't exist)")
        String branchName,

        @Schema(description = "Base branch to branch from (default: develop)", example = "develop")
        String targetBranch,

        @Schema(description = "Shared rule names to load from the rules repo")
        List<String> ruleNames,

        @Schema(description = "Extra rules appended to the system prompt")
        String extraRules,

        @Schema(description = "n8n webhook URL for completion callback")
        String n8nWebhookUrl,

        @Schema(description = "If true, commit directly to targetBranch instead of creating a PR (default: false)")
        Boolean commitDirect
) implements JobPayload {
    public String targetBranchOrDefault() {
        return (targetBranch != null && !targetBranch.isBlank()) ? targetBranch : "main";
    }

    public boolean isCommitDirect() {
        return commitDirect != null && commitDirect;
    }
}
