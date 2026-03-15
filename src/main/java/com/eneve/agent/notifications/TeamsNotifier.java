package com.eneve.agent.notifications;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.eneve.agent.model.RunResult;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * One-way Microsoft Teams notification via incoming webhook.
 * Sends Adaptive Card with job result summary.
 */
@ApplicationScoped
public class TeamsNotifier {

    private static final Logger LOG = Logger.getLogger(TeamsNotifier.class);

    @ConfigProperty(name = "teams.webhook.url", defaultValue = "")
    String webhookUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public void sendNotification(RunResult result) {
        if (webhookUrl.isBlank()) {
            LOG.debug("Teams webhook URL not configured, skipping notification");
            return;
        }

        String color = result.success() ? "Good" : "Attention";
        String status = result.success() ? "SUCCESS" : "FAILED";

        boolean hasStats = result.filesChanged() > 0 || result.linesChanged() > 0;
        String body = result.success()
                ? (result.prUrl() != null && !result.prUrl().isBlank()
                        ? "PR: " + result.prUrl() + "\\n\\n" : "")
                  + "Summary: " + escape(result.summary())
                  + (hasStats ? "\\n\\nFiles changed: " + result.filesChanged()
                        + " | Lines changed: " + result.linesChanged() : "")
                : "Error: " + escape(result.errorMessage());

        String repoLabel = repoSlug(result.repoUrl());

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
                          "text": "Code Agent: %s [%s]",
                          "style": "%s"
                        },
                        {
                          "type": "FactSet",
                          "facts": [
                            {"title": "Job", "value": "%s"},
                            {"title": "Repo", "value": "%s"},
                            {"title": "JIRA", "value": "%s"},
                            {"title": "Branch / PR", "value": "%s"}
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
                status, escape(result.jiraKey()),
                color.equals("Good") ? "default" : "attention",
                escape(result.jobId()),
                escape(repoLabel),
                escape(result.jiraKey()),
                escape(result.branchName()),
                body
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
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
