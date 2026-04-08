package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "AWS account configuration for an environment")
public record AwsConfig(
        @Schema(description = "AWS account ID", example = "123456789012")
        String accountId,

        @Schema(description = "AWS region", example = "eu-central-1")
        String region,

        @Schema(description = "IAM role ARN the agent may assume for read-only cloud access",
                example = "arn:aws:iam::123456789012:role/agent-readonly")
        String iamRole
) {}
