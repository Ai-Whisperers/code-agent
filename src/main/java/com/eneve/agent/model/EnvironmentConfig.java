package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A deployment environment for a product")
public record EnvironmentConfig(
        @Schema(description = "Environment name", example = "acceptance")
        String name,

        @Schema(description = "AWS account and region for this environment")
        AwsConfig aws
) {}
