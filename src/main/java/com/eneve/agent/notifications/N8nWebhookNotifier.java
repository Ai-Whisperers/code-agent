package com.eneve.agent.notifications;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.jboss.logging.Logger;

import com.eneve.agent.model.RunResult;
import com.eneve.agent.security.SsrfGuard;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Sends job results to n8n via webhook.
 * n8n uses this to orchestrate the approval flow (Teams notification, wait, approve/reject).
 */
@ApplicationScoped
public class N8nWebhookNotifier {

    private static final Logger LOG = Logger.getLogger(N8nWebhookNotifier.class);

    @Inject HttpClient httpClient;
    @Inject ObjectMapper objectMapper;

    /**
     * Send job result to n8n. Uses the webhook URL from the request, or the default from config.
     */
    public void sendResult(String webhookUrl, RunResult result) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            LOG.debug("n8n webhook URL not provided, skipping");
            return;
        }

        String ssrfError = SsrfGuard.validatePublicUrl(webhookUrl);
        if (ssrfError != null) {
            LOG.warnf("n8n webhook blocked (SSRF): %s — %s", webhookUrl, ssrfError);
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(result);

            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
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
