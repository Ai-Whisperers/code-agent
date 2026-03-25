package com.eneve.agent.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Top-level customer configuration")
public record CustomerConfig(
        @Schema(required = true, description = "Unique customer identifier (slug)", example = "acme-corp")
        String customerId,

        @Schema(required = true, description = "Human-readable customer name", example = "Acme Corp")
        String name,

        @Schema(description = "ID of the global cloud account (credentials) used for this customer's environments")
        String cloudAccountId,

        @Schema(description = "Deployment environments (production, acceptance, etc.)")
        List<EnvironmentConfig> environments,

        @Schema(description = "Free-form metadata for custom fields")
        Map<String, Object> metadata,

        @Schema(readOnly = true)
        Instant createdAt,

        @Schema(readOnly = true)
        Instant updatedAt
) {}
