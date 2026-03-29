package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to sync existing documentation files from a repository to Confluence.
 * Reads all Markdown files from the configured docs folder and publishes them
 * without an AI agent loop — fast, cheap, and deterministic.
 */
@Schema(description = "Confluence sync job request")
public record SyncConfluenceRequest(
        @Schema(description = "Git clone URL (HTTPS or SSH) of the repository", required = true)
        String repoUrl,

        @Schema(description = "Branch to read docs from (default: main)", example = "main")
        String branchName,

        @Schema(description = "Path inside the repo containing Markdown files (default: docs)", example = "docs")
        String docsPath,

        @Schema(description = "Override the Confluence space key from repo settings")
        String confluenceSpaceKey,

        @Schema(description = "Override the Confluence parent page ID from repo settings")
        String confluenceParentPageId
) implements JobPayload {
    public String branchOrDefault() {
        return (branchName != null && !branchName.isBlank()) ? branchName : "main";
    }

    public String docsPathOrDefault() {
        return (docsPath != null && !docsPath.isBlank()) ? docsPath : "docs";
    }
}
