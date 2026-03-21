package com.eneve.agent.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A deployment environment for a product")
public record EnvironmentConfig(
        @Schema(description = "Environment name", example = "acceptance")
        String name,

        @Schema(description = "AWS account and region for this environment")
        AwsConfig aws,

        @Schema(description = "Repository slugs deployed in this environment",
                example = "[\"frontend\",\"backend-api\",\"worker\"]")
        List<String> deployedRepos
) {}
