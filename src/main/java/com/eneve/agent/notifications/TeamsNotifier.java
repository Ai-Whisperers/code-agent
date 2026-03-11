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
        String body = result.success()
                ? "PR: " + result.prUrl() + "\\n\\nSummary: " + escape(result.summary())
                  + "\\n\\nFiles changed: " + result.filesChanged()
                  + " | Lines changed: " + result.linesChanged()
                : "Error: " + escape(result.errorMessage());

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
                            {"title": "JIRA", "value": "%s"},
                            {"title": "Branch", "value": "%s"}
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
}
