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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Fetches the latest stable Quarkus platform version from the Maven Central repository.
 *
 * <p>Reads {@code maven-metadata.xml} directly from {@code repo1.maven.org} rather than the
 * Maven Central search API ({@code search.maven.org}), whose Solr index can lag the actual
 * repository by many months and therefore returns stale version data.
 *
 * <p>Results are cached for a configurable duration to avoid hammering the endpoint.
 * Pre-release versions (CR, RC, Alpha, Beta, M, SNAPSHOT, Final) are always excluded.
 */
@ApplicationScoped
public class MavenCentralClient {

    private static final Logger LOG = Logger.getLogger(MavenCentralClient.class);

    private static final String MAVEN_METADATA_URL =
            "https://repo1.maven.org/maven2/io/quarkus/platform/quarkus-bom/maven-metadata.xml";

    /** Matches purely numeric version strings such as 3.20.1 — no pre-release qualifiers. */
    private static final Pattern STABLE_VERSION_PATTERN = Pattern.compile("^\\d+(\\.\\d+)*$");

    /** Extracts every {@code <version>…</version>} element from maven-metadata.xml. */
    private static final Pattern VERSION_TAG_PATTERN = Pattern.compile("<version>([^<]+)</version>");

    @Inject SettingsService settings;

    private volatile String cachedVersion;
    private volatile Instant cacheExpiry = Instant.EPOCH;

    @Inject HttpClient httpClient;

    /**
     * Returns the latest stable Quarkus platform version, using a cached result when available.
     * Pre-release versions (CR, RC, Alpha, Beta, M, SNAPSHOT) are skipped.
     *
     * @return latest stable version string (e.g. {@code "3.32.3"}), or empty on failure
     */
    public Optional<String> getLatestQuarkusVersion() {
        if (cachedVersion != null && Instant.now().isBefore(cacheExpiry)) {
            LOG.debugf("MavenCentralClient: returning cached Quarkus version %s", cachedVersion);
            return Optional.of(cachedVersion);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MAVEN_METADATA_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/xml, text/xml")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("MavenCentralClient: maven-metadata.xml returned HTTP %d", response.statusCode());
                return Optional.empty();
            }

            List<String> stableVersions = new ArrayList<>();
            Matcher m = VERSION_TAG_PATTERN.matcher(response.body());
            while (m.find()) {
                String v = m.group(1).trim();
                if (isStableVersion(v)) {
                    stableVersions.add(v);
                }
            }

            if (stableVersions.isEmpty()) {
                LOG.warnf("MavenCentralClient: no stable versions found in maven-metadata.xml");
                return Optional.empty();
            }

            String latest = stableVersions.stream()
                    .max(MavenCentralClient::compareVersions)
                    .orElseThrow();

            LOG.infof("MavenCentralClient: latest stable Quarkus version from maven-metadata.xml is %s", latest);
            return cacheAndReturn(latest);

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

    private Optional<String> cacheAndReturn(String version) {
        cachedVersion = version;
        long cacheDurationMinutes = Long.parseLong(settings.get("upgrade.scheduler.version-cache-minutes", "60"));
        cacheExpiry = Instant.now().plusSeconds(cacheDurationMinutes * 60);
        LOG.infof("MavenCentralClient: latest Quarkus version is %s (cached for %d min)",
                version, cacheDurationMinutes);
        return Optional.of(version);
    }
}
