package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to generate a Structurizr DSL architecture model for a repository.
 * The agent explores the codebase and writes {@code docs/architecture.dsl},
 * which is then validated, exported to Mermaid, and stored as a versioned diagram.
 */
@Schema(description = "Architecture generation job request")
public record GenerateArchitectureRequest(
        @Schema(description = "Git clone URL (HTTPS or SSH) of the repository", required = true)
        String repoUrl,

        @Schema(description = "Branch to generate architecture on (created from targetBranch if it doesn't exist)")
        String branchName,

        @Schema(description = "Base branch to branch from (default: main)")
        String targetBranch,

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
