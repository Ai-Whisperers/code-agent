package com.eneve.agent.model;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Jira project configuration for a product")
public record JiraProjectConfig(
        @Schema(description = "Jira base URL; if blank uses the global jira.base.url setting",
                example = "https://myorg.atlassian.net")
        String baseUrl,

        @Schema(description = "Jira project keys by role",
                example = "{\"serviceDesk\":\"SD\",\"engineering\":\"ENG\",\"devops\":\"OPS\"}")
        Map<String, String> projects
) {}
