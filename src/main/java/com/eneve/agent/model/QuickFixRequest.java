package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Simplified request to submit a fix job using only the JIRA key and repo URL. "
        + "The prompt is fetched from the JIRA ticket description, the branch is auto-generated "
        + "as agent/{JIRA_KEY}-{summary-slug}, and develop is used as the base branch.")
public record QuickFixRequest(

        @Schema(required = true, description = "Bitbucket Cloud repo URL (HTTPS or SSH)",
                example = "https://bitbucket.org/csarenergy/ms-meter.git")
        String repoUrl,

        @Schema(required = true, description = "JIRA issue key — the summary and description will be used as the prompt",
                example = "JTP-10967")
        String jiraKey
) {
}
