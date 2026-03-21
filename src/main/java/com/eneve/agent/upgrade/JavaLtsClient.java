package com.eneve.agent.upgrade;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Fetches the current Java LTS version from the Eclipse Adoptium (Temurin) API.
 *
 * <p>Calls {@code https://api.adoptium.net/v3/info/available_releases} and reads the
 * {@code most_recent_lts} field, returning it as a plain integer string (e.g. {@code "21"}).
 *
 * <p>This client is informational only — it is used to populate the
 * {@code GET /upgrades/latest-versions} response. Java is not a standalone archetype in the
 * upgrade service; Java-based projects are tracked as {@code quarkus} or {@code wildfly}.
 *
 * <p>Results are cached for a configurable duration to avoid repeated outbound calls.
 */
@ApplicationScoped
public class JavaLtsClient {

    private static final Logger LOG = Logger.getLogger(JavaLtsClient.class);

    private static final String AVAILABLE_RELEASES_URL =
            "https://api.adoptium.net/v3/info/available_releases";

    @ConfigProperty(name = "upgrade.scheduler.version-cache-minutes", defaultValue = "60")
    long cacheDurationMinutes;

    private volatile String cachedVersion;
    private volatile Instant cacheExpiry = Instant.EPOCH;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Returns the current Java LTS major version (e.g. {@code "21"}),
     * using a cached result when available.
     *
     * @return Java LTS major version string, or empty on failure
     */
    public Optional<String> getLatestJavaLtsVersion() {
        if (cachedVersion != null && Instant.now().isBefore(cacheExpiry)) {
            LOG.debugf("JavaLtsClient: returning cached Java LTS version %s", cachedVersion);
            return Optional.of(cachedVersion);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(AVAILABLE_RELEASES_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("JavaLtsClient: Adoptium API returned HTTP %d", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = new ObjectMapper().readTree(response.body());
            JsonNode ltsNode = root.get("most_recent_lts");
            if (ltsNode == null || ltsNode.isNull()) {
                LOG.warnf("JavaLtsClient: 'most_recent_lts' field missing from Adoptium response");
                return Optional.empty();
            }

            String version = String.valueOf(ltsNode.asInt());
            return cacheAndReturn(version);

        } catch (Exception e) {
            LOG.errorf("JavaLtsClient: failed to fetch Java LTS version: %s", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> cacheAndReturn(String version) {
        cachedVersion = version;
        cacheExpiry = Instant.now().plusSeconds(cacheDurationMinutes * 60);
        LOG.infof("JavaLtsClient: current Java LTS version is %s (cached for %d min)",
                version, cacheDurationMinutes);
        return Optional.of(version);
    }
}
