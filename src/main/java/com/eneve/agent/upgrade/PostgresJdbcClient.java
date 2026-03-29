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
 * Fetches the latest stable PostgreSQL JDBC driver version from the Maven Central repository.
 *
 * <p>Reads {@code maven-metadata.xml} for {@code org.postgresql:postgresql} directly from
 * {@code repo1.maven.org} and selects the highest version matching the stable numeric pattern
 * (digits and dots only, excluding {@code -jreN}, {@code -SNAPSHOT}, and other qualifiers).
 *
 * <p>Results are cached for a configurable duration to avoid repeated outbound calls.
 */
@ApplicationScoped
public class PostgresJdbcClient {

    private static final Logger LOG = Logger.getLogger(PostgresJdbcClient.class);

    private static final String MAVEN_METADATA_URL =
            "https://repo1.maven.org/maven2/org/postgresql/postgresql/maven-metadata.xml";

    /** Matches purely numeric version strings such as 42.7.3 — no pre-release qualifiers. */
    private static final Pattern STABLE_VERSION_PATTERN = Pattern.compile("^\\d+(\\.\\d+)*$");

    private static final Pattern VERSION_TAG_PATTERN =
            Pattern.compile("<version>([^<]+)</version>");

    @Inject SettingsService settings;

    private volatile String cachedVersion;
    private volatile Instant cacheExpiry = Instant.EPOCH;

    @Inject HttpClient httpClient;

    /**
     * Returns the latest stable PostgreSQL JDBC driver version (e.g. {@code "42.7.3"}),
     * using a cached result when available.
     *
     * @return latest stable version string, or empty on failure
     */
    public Optional<String> getLatestPostgresJdbcVersion() {
        if (cachedVersion != null && Instant.now().isBefore(cacheExpiry)) {
            LOG.debugf("PostgresJdbcClient: returning cached version %s", cachedVersion);
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
                LOG.warnf("PostgresJdbcClient: maven-metadata.xml returned HTTP %d", response.statusCode());
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
                LOG.warnf("PostgresJdbcClient: no stable versions found in maven-metadata.xml");
                return Optional.empty();
            }

            String latest = stableVersions.stream()
                    .max(PostgresJdbcClient::compareVersions)
                    .orElseThrow();

            return cacheAndReturn(latest);

        } catch (Exception e) {
            LOG.errorf("PostgresJdbcClient: failed to fetch PostgreSQL JDBC version: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} if {@code version} is a stable release — i.e. contains only
     * digits and dots with no pre-release qualifiers ({@code -jreN}, {@code -SNAPSHOT}, etc.).
     */
    static boolean isStableVersion(String version) {
        return version != null && STABLE_VERSION_PATTERN.matcher(version).matches();
    }

    /**
     * Compares two version strings semantically by splitting on {@code '.'} and comparing
     * each numeric segment left-to-right.
     */
    static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int segA = i < partsA.length ? parseNum(partsA[i]) : 0;
            int segB = i < partsB.length ? parseNum(partsB[i]) : 0;
            if (segA != segB) return Integer.compare(segA, segB);
        }
        return 0;
    }

    private static int parseNum(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Optional<String> cacheAndReturn(String version) {
        cachedVersion = version;
        long cacheDurationMinutes = Long.parseLong(settings.get("upgrade.scheduler.version-cache-minutes", "60"));
        cacheExpiry = Instant.now().plusSeconds(cacheDurationMinutes * 60);
        LOG.infof("PostgresJdbcClient: latest stable PostgreSQL JDBC version is %s (cached for %d min)",
                version, cacheDurationMinutes);
        return Optional.of(version);
    }
}
