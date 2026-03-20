package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Git platform configuration for a product")
public record GitConfig(
        @Schema(description = "Git platform identifier",
                example = "bitbucket",
                enumeration = {"bitbucket", "github", "gitlab", "azuredevops"})
        String platform,

        @Schema(description = "Workspace or organisation slug", example = "myorg")
        String workspace,

        @Schema(description = "Base URL for self-hosted platforms; omit for cloud",
                example = "https://gitlab.example.com")
        String baseUrl
) {}
