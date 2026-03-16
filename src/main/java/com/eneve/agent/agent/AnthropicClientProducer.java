package com.eneve.agent.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * CDI producer for the shared Anthropic API client.
 * Sharing a single application-scoped client reuses OkHttp's connection pool
 * and avoids TLS handshake overhead on every individual API call.
 */
@ApplicationScoped
public class AnthropicClientProducer {

    private static final Logger LOG = Logger.getLogger(AnthropicClientProducer.class);

    @ConfigProperty(name = "anthropic.api.key")
    String apiKey;

    @Produces
    @ApplicationScoped
    public AnthropicClient anthropicClient() {
        LOG.info("Creating shared AnthropicClient (connection pool reused across all callers)");
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }
}
