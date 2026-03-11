package com.eneve.agent.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Request to submit an Aikido-driven fix job. Provide a JIRA key (linked to an Aikido issue) "
        + "or an Aikido issue group ID. The agent resolves the repository, package, versions, and CVE context "
        + "from Aikido and uses develop as the base branch.")
public record AikidoFixRequest(

        @Schema(description = "JIRA issue key linked to an Aikido vulnerability. "
                + "The agent will search Aikido for the issue group linked to this JIRA key.",
                example = "JTP-10967")
        String jiraKey,

        @Schema(description = "Aikido issue group ID. If provided, used directly instead of searching by JIRA key.",
                example = "12345")
        Integer aikidoGroupId,

        @Schema(description = "Override repo URL. If empty, resolved from Aikido issue metadata.",
                example = "https://bitbucket.org/csarenergy/ms-meter.git")
        String repoUrl,

        @Schema(description = "List of rule names to load from the shared rules repo (configured via RULES_REPO_URL)",
                example = "[\"java-conventions\", \"maven-standards\"]")
        List<String> ruleNames,

        @Schema(description = "Inline extra rules to append to the system prompt",
                example = "Do not modify test files")
        String extraRules
) {
}
