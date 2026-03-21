package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A team member with contact references")
public record TeamMember(
        @Schema(description = "Full name", example = "Alice van Dam")
        String name,

        @Schema(description = "Work email address", example = "alice@example.com")
        String email,

        @Schema(description = "Jira account ID for @mentions and assignments",
                example = "5f8b2c1a3e4d0a001234abcd")
        String jiraAccountId,

        @Schema(description = "Slack member ID for notifications", example = "U012AB3CD")
        String slackId
) {}
