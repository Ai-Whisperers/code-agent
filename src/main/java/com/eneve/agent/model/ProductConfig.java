package com.eneve.agent.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Product configuration including environments, teams, and tool integrations")
public record ProductConfig(
        @Schema(required = true, description = "Unique product identifier (slug)", example = "myproduct-platform")
        String productId,

        @Schema(description = "Customer this product is linked to (null when product is unlinked)", example = "acme-corp")
        String customerId,

        @Schema(required = true, description = "Human-readable product name", example = "MyProduct Platform")
        String displayName,

        @Schema(description = "Git platform configuration")
        GitConfig git,

        @Schema(description = "Jira project configuration")
        JiraProjectConfig jira,

        @Schema(description = "Confluence space configuration")
        ConfluenceProductConfig confluence,

        @Schema(description = "Deployment environments (acceptance, production, etc.)")
        List<EnvironmentConfig> environments,

        @Schema(description = "Team members by role. Role keys: productOwner, engineering, devops, operations, qa, security, supportQueue")
        Map<String, List<TeamMember>> teams,

        @Schema(description = "Free-form metadata for custom fields")
        Map<String, Object> metadata,

        @Schema(readOnly = true)
        Instant createdAt,

        @Schema(readOnly = true)
        Instant updatedAt
) {}
