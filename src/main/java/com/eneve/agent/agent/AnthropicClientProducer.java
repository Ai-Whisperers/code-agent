package com.eneve.agent.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.eneve.agent.settings.SettingsService;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI producer for the shared Anthropic API client.
 * Sharing a single application-scoped client reuses OkHttp's connection pool
 * and avoids TLS handshake overhead on every individual API call.
 *
 * The API key is resolved at startup via SettingsService, which checks the
 * agent_settings DB table first and falls back to the ANTHROPIC_API_KEY env var.
 * Rotating the key requires a restart because the OkHttp connection pool holds
 * the initial value.
 */
@ApplicationScoped
public class AnthropicClientProducer {

    private static final Logger LOG = Logger.getLogger(AnthropicClientProducer.class);

    @Inject
    SettingsService settingsService;

    @Produces
    @ApplicationScoped
    public AnthropicClient anthropicClient() {
        String apiKey = settingsService.getSecret("anthropic.api.key");
        LOG.info("Creating shared AnthropicClient (connection pool reused across all callers)");
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }
}
