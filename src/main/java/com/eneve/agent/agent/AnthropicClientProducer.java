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
 * <h2>AIW adaptation: LiteLLM gateway</h2>
 *
 * <p>When {@code anthropic.base.url} is set (normally via the
 * {@code LITELLM_GATEWAY_URL} env var), the client is pointed at our LiteLLM
 * gateway's Anthropic-compatible passthrough at {@code /v1/messages} instead
 * of directly at {@code api.anthropic.com}. The Anthropic Java SDK works
 * unchanged because LiteLLM speaks Anthropic's wire format natively.
 *
 * <p>Benefits of going through LiteLLM:
 * <ul>
 *   <li>Multi-provider routing: model aliases like {@code primary},
 *       {@code fast}, {@code reasoning} resolve to whichever underlying
 *       provider (Anthropic, Groq, Cerebras, etc.) gives the best
 *       price/latency for that tier.</li>
 *   <li>One shared budget and spend tracking across all agents.</li>
 *   <li>Per-team key scoping and rate limits managed in one place.</li>
 *   <li>No provider API key ever leaves the LiteLLM host.</li>
 * </ul>
 *
 * <p>The API key is resolved at startup via SettingsService, which checks the
 * agent_settings DB table first and falls back to the ANTHROPIC_API_KEY env
 * var. When routing through LiteLLM, this key is the LiteLLM master key, not
 * an Anthropic key.
 *
 * <p>Rotating the key requires a restart because the OkHttp connection pool
 * holds the initial value.
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
        String baseUrl = settingsService.get("anthropic.base.url");

        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey);

        if (baseUrl != null && !baseUrl.isBlank()) {
            // Pointing at LiteLLM (or any Anthropic-compatible gateway) instead of api.anthropic.com
            LOG.infof("Creating shared AnthropicClient routed through gateway: %s", baseUrl);
            builder.baseUrl(baseUrl);
        } else {
            LOG.info("Creating shared AnthropicClient (direct to api.anthropic.com)");
        }

        return builder.build();
    }
}
