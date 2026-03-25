package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A deployment environment for a customer")
public record EnvironmentConfig(
        @Schema(description = "Environment name", example = "Engie Netherlands Production")
        String name,

        @Schema(description = "Deployment stage — one of: production, acceptance, test, development, other",
                example = "production")
        String type,

        @Schema(description = "AWS account and region for this environment")
        AwsConfig aws
) {}
