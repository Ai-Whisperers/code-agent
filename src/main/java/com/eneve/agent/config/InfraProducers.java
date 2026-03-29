package com.eneve.agent.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * CDI producers for shared infrastructure singletons.
 *
 * <p>Quarkus (via {@code quarkus-rest-jackson}) already produces an {@link com.fasterxml.jackson.databind.ObjectMapper}
 * CDI bean with Java-time support — use {@code @Inject ObjectMapper} directly.
 *
 * <p>This class adds a shared {@link HttpClient} singleton, which the JDK does not provide
 * as a CDI bean. Services that need non-default HTTP settings (custom timeouts, redirect
 * policies) should build their own client locally and document the deviation.
 */
@ApplicationScoped
public class InfraProducers {

    /**
     * Shared {@link HttpClient} with a 15-second connect timeout and automatic redirect following.
     * Inject with {@code @Inject HttpClient httpClient}.
     *
     * <p>Do <em>not</em> use this for services that need a different connect timeout
     * (e.g. {@code VoyageEmbeddingService} uses 10 s) or configurable timeouts
     * ({@code FetchUrlTool}, {@code WebDocsCrawlerService}).
     */
    @Produces
    @ApplicationScoped
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
