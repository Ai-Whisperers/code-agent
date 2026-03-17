package com.eneve.agent.upgrade;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Fetches the latest stable PHP release version from the php.net releases JSON API.
 *
 * <p>Calls {@code https://www.php.net/releases/index.php?json}, which returns a JSON object
 * whose keys are version strings (e.g. {@code "8.3.11"}, {@code "8.2.23"}).  Only purely
 * numeric semver versions ({@code major.minor.patch}) are considered stable.
 *
 * <p>Results are cached for a configurable duration to avoid repeated outbound calls.
 */
@ApplicationScoped
public class PhpReleaseClient {

    private static final Logger LOG = Logger.getLogger(PhpReleaseClient.class);

    private static final String PHP_RELEASES_URL = "https://www.php.net/releases/index.php?json";

    /** Matches purely numeric PHP release versions like {@code 8.3.11}. */
    private static final Pattern STABLE_VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    @ConfigProperty(name = "upgrade.scheduler.version-cache-minutes", defaultValue = "60")
    long cacheDurationMinutes;

    private volatile String cachedVersion;
    private volatile Instant cacheExpiry = Instant.EPOCH;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Returns the latest stable PHP release version (e.g. {@code "8.3.11"}),
     * using a cached result when available.
     *
     * @return latest stable version string, or empty on failure
     */
    public Optional<String> getLatestPhpVersion() {
        if (cachedVersion != null && Instant.now().isBefore(cacheExpiry)) {
            LOG.debugf("PhpReleaseClient: returning cached PHP version %s", cachedVersion);
            return Optional.of(cachedVersion);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PHP_RELEASES_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("PhpReleaseClient: PHP releases API returned HTTP %d", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = new ObjectMapper().readTree(response.body());

            List<String> stableVersions = new ArrayList<>();
            root.fieldNames().forEachRemaining(key -> {
                if (STABLE_VERSION_PATTERN.matcher(key).matches()) {
                    stableVersions.add(key);
                }
            });

            if (stableVersions.isEmpty()) {
                LOG.warnf("PhpReleaseClient: no stable PHP versions found in API response");
                return Optional.empty();
            }

            String latest = stableVersions.stream()
                    .max(MavenCentralClient::compareVersions)
                    .orElseThrow();

            return cacheAndReturn(latest);

        } catch (Exception e) {
            LOG.errorf("PhpReleaseClient: failed to fetch PHP version: %s", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> cacheAndReturn(String version) {
        cachedVersion = version;
        cacheExpiry = Instant.now().plusSeconds(cacheDurationMinutes * 60);
        LOG.infof("PhpReleaseClient: latest stable PHP version is %s (cached for %d min)",
                version, cacheDurationMinutes);
        return Optional.of(version);
    }
}
