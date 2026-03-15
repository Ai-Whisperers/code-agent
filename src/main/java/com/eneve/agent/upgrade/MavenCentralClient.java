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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Fetches the latest stable Quarkus platform version from Maven Central search API.
 * Results are cached for a configurable duration to avoid hammering the search endpoint.
 * Pre-release versions (CR, RC, Alpha, Beta, M, SNAPSHOT) are always excluded.
 */
@ApplicationScoped
public class MavenCentralClient {

    private static final Logger LOG = Logger.getLogger(MavenCentralClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String QUARKUS_BOM_URL =
            "https://search.maven.org/solrsearch/select"
            + "?q=g:io.quarkus.platform+AND+a:quarkus-bom&rows=1&wt=json";

    private static final String QUARKUS_BOM_GAV_URL =
            "https://search.maven.org/solrsearch/select"
            + "?q=g:io.quarkus.platform+AND+a:quarkus-bom&core=gav&rows=40&wt=json";

    /** Matches purely numeric version strings such as 3.20.1 — no pre-release qualifiers. */
    private static final Pattern STABLE_VERSION_PATTERN = Pattern.compile("^\\d+(\\.\\d+)*$");

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
     * Pre-release versions (CR, RC, Alpha, Beta, M, SNAPSHOT) are skipped.
     *
     * @return latest stable version string (e.g. {@code "3.20.1"}), or empty on failure
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

            if (!isStableVersion(version)) {
                LOG.warnf("MavenCentralClient: latestVersion '%s' is a pre-release — falling back to GAV listing", version);
                return fetchLatestStableFromGav();
            }

            return cacheAndReturn(version);

        } catch (Exception e) {
            LOG.errorf("MavenCentralClient: failed to fetch Quarkus version: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} if {@code version} is a stable release — i.e. contains only
     * digits and dots with no pre-release qualifiers (CR, RC, Alpha, Beta, M, SNAPSHOT, etc.).
     */
    static boolean isStableVersion(String version) {
        return version != null && STABLE_VERSION_PATTERN.matcher(version).matches();
    }

    /**
     * Compares two version strings semantically by splitting on {@code '.'} and comparing
     * each numeric segment left-to-right.
     *
     * @return negative if {@code a < b}, zero if equal, positive if {@code a > b}
     */
    static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int segA = i < partsA.length ? Integer.parseInt(partsA[i]) : 0;
            int segB = i < partsB.length ? Integer.parseInt(partsB[i]) : 0;
            if (segA != segB) {
                return Integer.compare(segA, segB);
            }
        }
        return 0;
    }

    /**
     * Queries the Maven Central GAV listing for all published {@code quarkus-bom} versions,
     * filters out pre-releases, and returns the highest stable version found.
     */
    private Optional<String> fetchLatestStableFromGav() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(QUARKUS_BOM_GAV_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("MavenCentralClient: GAV listing returned HTTP %d", response.statusCode());
                return Optional.empty();
            }

            JsonNode docs = MAPPER.readTree(response.body()).path("response").path("docs");
            if (!docs.isArray() || docs.isEmpty()) {
                LOG.warnf("MavenCentralClient: GAV listing returned no docs");
                return Optional.empty();
            }

            List<String> stableVersions = new ArrayList<>();
            for (JsonNode doc : docs) {
                String v = doc.path("v").asText(null);
                if (isStableVersion(v)) {
                    stableVersions.add(v);
                }
            }

            if (stableVersions.isEmpty()) {
                LOG.warnf("MavenCentralClient: no stable versions found in GAV listing");
                return Optional.empty();
            }

            String latest = stableVersions.stream()
                    .max(MavenCentralClient::compareVersions)
                    .orElseThrow();

            LOG.infof("MavenCentralClient: latest stable Quarkus version from GAV listing is %s", latest);
            return cacheAndReturn(latest);

        } catch (Exception e) {
            LOG.errorf("MavenCentralClient: failed to fetch GAV listing: %s", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> cacheAndReturn(String version) {
        cachedVersion = version;
        cacheExpiry = Instant.now().plusSeconds(cacheDurationMinutes * 60);
        LOG.infof("MavenCentralClient: latest Quarkus version is %s (cached for %d min)",
                version, cacheDurationMinutes);
        return Optional.of(version);
    }
}
