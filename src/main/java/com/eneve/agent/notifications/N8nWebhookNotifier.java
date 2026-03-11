package com.eneve.agent.notifications;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.jboss.logging.Logger;

import com.eneve.agent.model.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Sends job results to n8n via webhook.
 * n8n uses this to orchestrate the approval flow (Teams notification, wait, approve/reject).
 */
@ApplicationScoped
public class N8nWebhookNotifier {

    private static final Logger LOG = Logger.getLogger(N8nWebhookNotifier.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Send job result to n8n. Uses the webhook URL from the request, or the default from config.
     */
    public void sendResult(String webhookUrl, RunResult result) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            LOG.debug("n8n webhook URL not provided, skipping");
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(result);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.infof("n8n webhook sent (HTTP %d) to %s", response.statusCode(), webhookUrl);
        } catch (Exception e) {
            LOG.errorf("n8n webhook failed for %s: %s", webhookUrl, e.getMessage());
        }
    }
}
