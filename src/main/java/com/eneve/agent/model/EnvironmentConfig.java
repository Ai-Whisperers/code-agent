package com.eneve.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "A deployment environment for a customer")
public record EnvironmentConfig(
        @Schema(description = "Environment name", example = "Engie Netherlands Production")
        String name,

        @Schema(description = "Deployment stage — one of: production, acceptance, test, development, other",
                example = "production")
        String type,

        @Schema(description = "AWS account and region for this environment")
        AwsConfig aws,

        @Schema(description = "Log analysis configuration — when present and enabled, the scheduler will scan this environment's CloudWatch logs")
        LogAnalysisConfig logAnalysis
) {}
