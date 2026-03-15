package com.eneve.agent.upgrade;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Fetches the latest stable Quarkus platform version from Maven Central search API.
 * Results are cached for a configurable duration to avoid hammering the search endpoint.
 */
@ApplicationScoped
public class MavenCentralClient {

    private static final Logger LOG = Logger.getLogger(MavenCentralClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String QUARKUS_BOM_URL =
            "https://search.maven.org/solrsearch/select"
            + "?q=g:io.quarkus.platform+AND+a:quarkus-bom&rows=1&wt=json";

    @ConfigProperty(name = "upgrade.scheduler.version-cache-minutes", defaultValue = "60")
    long cacheDurationMinutes;

    private volatile String cachedVersion;
    private volatile Instant cacheExpiry = Instant.EPOCH;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Returns the latest stable Quarkus platform version, using a cached result when available.
     *
     * @return latest version string (e.g. {@code "3.17.0"}), or empty on failure
     */
    public Optional<String> getLatestQuarkusVersion() {
        if (cachedVersion != null && Instant.now().isBefore(cacheExpiry)) {
            LOG.debugf("MavenCentralClient: returning cached Quarkus version %s", cachedVersion);
            return Optional.of(cachedVersion);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(QUARKUS_BOM_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("MavenCentralClient: Maven Central returned HTTP %d", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode docs = root.path("response").path("docs");
            if (!docs.isArray() || docs.isEmpty()) {
                LOG.warnf("MavenCentralClient: no docs in Maven Central response");
                return Optional.empty();
            }

            String version = docs.get(0).path("latestVersion").asText(null);
            if (version == null || version.isBlank()) {
                LOG.warnf("MavenCentralClient: latestVersion field missing in response");
                return Optional.empty();
            }

            cachedVersion = version;
            cacheExpiry = Instant.now().plusSeconds(cacheDurationMinutes * 60);
            LOG.infof("MavenCentralClient: latest Quarkus version is %s (cached for %d min)",
                    version, cacheDurationMinutes);
            return Optional.of(version);

        } catch (Exception e) {
            LOG.errorf("MavenCentralClient: failed to fetch Quarkus version: %s", e.getMessage());
            return Optional.empty();
        }
    }
}
