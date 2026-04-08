package com.eneve.agent.model;

import java.time.Instant;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A named cloud provider account with credentials")
public record CloudAccount(
        @Schema(required = true, description = "Unique identifier slug", example = "my-aws-prod")
        String id,

        @Schema(required = true, description = "Human-readable display name", example = "Engie AWS Production")
        String name,

        @Schema(description = "Optional description of this cloud account")
        String description,

        @Schema(required = true, description = "Cloud provider type")
        CloudAccountType type,

        @Schema(description = "Provider-specific credential map. Secret values are masked (****) in API responses.")
        Map<String, String> credentials,

        @Schema(readOnly = true)
        Instant createdAt,

        @Schema(readOnly = true)
        Instant updatedAt
) {}
