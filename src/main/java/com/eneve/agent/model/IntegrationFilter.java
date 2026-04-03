package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Enable/disable filter for a Jira project or Confluence space")
public record IntegrationFilter(

        @Schema(readOnly = true)
        Long id,

        @Schema(required = true, description = "Integration type: 'jira' or 'confluence'", example = "jira")
        String integrationType,

        @Schema(required = true, description = "Jira project key or Confluence space key", example = "ENG")
        String key,

        @Schema(description = "Display name cached from the live API", example = "Engineering")
        String name,

        @Schema(description = "When false the project/space is excluded from indexing, webhooks, and UI selectors")
        boolean enabled,

        @Schema(description = "When false incoming webhooks for this project/space are silently ignored even if enabled=true")
        boolean webhookEnabled,

        @Schema(readOnly = true)
        Instant createdAt,

        @Schema(readOnly = true)
        Instant updatedAt
) {}
