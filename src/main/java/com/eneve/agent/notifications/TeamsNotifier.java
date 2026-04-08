package com.eneve.agent.notifications;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.jboss.logging.Logger;

import com.eneve.agent.model.RunResult;
import com.eneve.agent.settings.SettingsService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * One-way Microsoft Teams notification via incoming webhook.
 * Sends Adaptive Card with job result summary.
 */
@ApplicationScoped
public class TeamsNotifier {

    private static final Logger LOG = Logger.getLogger(TeamsNotifier.class);

    @Inject
    SettingsService settingsService;

    @Inject HttpClient httpClient;

    public void sendNotification(RunResult result) {
        String webhookUrl = settingsService.get("teams.webhook.url", "");
        if (webhookUrl.isBlank()) {
            LOG.debug("Teams webhook URL not configured, skipping notification");
            return;
        }

        String status = result.status() != null ? result.status() : "UNKNOWN";
        String cardStyle = switch (status) {
            case "FAILED"            -> "attention";
            case "AWAITING_APPROVAL" -> "accent";
            default                  -> "default";
        };
        String jobLabel = jobTypeLabel(result.jobType());

        boolean hasStats = result.filesChanged() > 0 || result.linesChanged() > 0;
        String body = switch (status) {
            case "FAILED" -> "Error: " + escape(result.errorMessage());
            case "STARTED" -> "Summary: " + escape(result.summary());
            case "AWAITING_APPROVAL" ->
                    (result.prUrl() != null && !result.prUrl().isBlank()
                            ? "PR: " + result.prUrl() + "\\n\\n" : "")
                    + escape(result.summary())
                    + "\\n\\nPlease review and approve the pull request.";
            default -> // SUCCESS and anything else
                    (result.prUrl() != null && !result.prUrl().isBlank()
                            ? "PR: " + result.prUrl() + "\\n\\n" : "")
                    + "Summary: " + escape(result.summary())
                    + (hasStats ? "\\n\\nFiles changed: " + result.filesChanged()
                            + " | Lines changed: " + result.linesChanged() : "");
        };

        String repoLabel = repoSlug(result.repoUrl());

        StringBuilder factsJson = new StringBuilder();
        factsJson.append("{\"title\": \"Job\", \"value\": \"").append(escape(result.jobId())).append("\"},\n");
        factsJson.append("            {\"title\": \"Type\", \"value\": \"").append(escape(jobLabel)).append("\"},\n");
        factsJson.append("            {\"title\": \"Repo\", \"value\": \"").append(escape(repoLabel)).append("\"}");
        if (result.jiraKey() != null && !result.jiraKey().isBlank()) {
            factsJson.append(",\n            {\"title\": \"JIRA\", \"value\": \"").append(escape(result.jiraKey())).append("\"}");
        }
        if (result.branchName() != null && !result.branchName().isBlank()) {
            factsJson.append(",\n            {\"title\": \"Branch / PR\", \"value\": \"").append(escape(result.branchName())).append("\"}");
        }

        String payload = """
                {
                  "type": "message",
                  "attachments": [{
                    "contentType": "application/vnd.microsoft.card.adaptive",
                    "content": {
                      "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                      "type": "AdaptiveCard",
                      "version": "1.4",
                      "body": [
                        {
                          "type": "TextBlock",
                          "size": "Medium",
                          "weight": "Bolder",
                          "text": "Code Agent %s: %s",
                          "style": "%s"
                        },
                        {
                          "type": "FactSet",
                          "facts": [
                            %s
                          ]
                        },
                        {
                          "type": "TextBlock",
                          "text": "%s",
                          "wrap": true
                        }
                      ]
                    }
                  }]
                }
                """.formatted(
                jobLabel, status,
                cardStyle,
                factsJson,
                body
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.infof("Teams notification sent (HTTP %d)", response.statusCode());
        } catch (Exception e) {
            LOG.errorf("Teams notification failed: %s", e.getMessage());
        }
    }

    private static String jobTypeLabel(String jobType) {
        if (jobType == null) return "Job";
        return switch (jobType) {
            case "FIX"             -> "Fix";
            case "REVIEW"          -> "Review";
            case "FIX_PR"          -> "Fix PR";
            case "REPLY"           -> "Reply";
            case "FIX_COMMENT"     -> "Fix Comment";
            case "HOOK"            -> "Hook";
            case "GENERATE_TESTS"  -> "Generate Tests";
            case "GENERATE_DOCS"   -> "Generate Docs";
            case "UPGRADE"         -> "Quarkus Upgrade";
            case "PROMOTE"         -> "Security Promotion";
            case "AIKIDO_TRIAGE"   -> "Aikido Triage";
            default -> jobType;
        };
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    /**
     * Extracts a human-readable "workspace/repo" slug from a clone URL.
     * e.g. "https://bitbucket.org/acme/my-service.git" -> "acme/my-service"
     * Falls back to the raw URL if parsing fails.
     */
    private static String repoSlug(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return "";
        String s = repoUrl.stripTrailing().replaceAll("\\.git$", "");
        int slash = s.lastIndexOf('/');
        if (slash <= 0) return s;
        int prevSlash = s.lastIndexOf('/', slash - 1);
        return prevSlash >= 0 ? s.substring(prevSlash + 1) : s.substring(slash + 1);
    }
}
