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
 * Fetches the latest stable WildFly release version from the Maven Central repository.
 *
 * <p>Reads {@code maven-metadata.xml} for {@code org.wildfly:wildfly-parent} directly from
 * {@code repo1.maven.org} and selects the highest version matching the stable
 * {@code <major>.<minor>.<patch>.Final} pattern.
 *
 * <p>Results are cached for a configurable duration to avoid repeated outbound calls.
 */
@ApplicationScoped
public class WildflyReleaseClient {

    private static final Logger LOG = Logger.getLogger(WildflyReleaseClient.class);

    private static final String MAVEN_METADATA_URL =
            "https://repo1.maven.org/maven2/org/wildfly/wildfly-parent/maven-metadata.xml";

    /** Matches stable WildFly releases like {@code 32.0.1.Final}. */
    private static final Pattern STABLE_VERSION_PATTERN =
            Pattern.compile("^\\d+\\.\\d+\\.\\d+\\.Final$");

    private static final Pattern VERSION_TAG_PATTERN =
            Pattern.compile("<version>([^<]+)</version>");

    @Inject SettingsService settings;

    private volatile String cachedVersion;
    private volatile Instant cacheExpiry = Instant.EPOCH;

    @Inject HttpClient httpClient;

    /**
     * Returns the latest stable WildFly release version (e.g. {@code "33.0.0.Final"}),
     * using a cached result when available.
     *
     * @return latest stable version string, or empty on failure
     */
    public Optional<String> getLatestWildflyVersion() {
        if (cachedVersion != null && Instant.now().isBefore(cacheExpiry)) {
            LOG.debugf("WildflyReleaseClient: returning cached WildFly version %s", cachedVersion);
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
                LOG.warnf("WildflyReleaseClient: maven-metadata.xml returned HTTP %d",
                        response.statusCode());
                return Optional.empty();
            }

            List<String> stableVersions = new ArrayList<>();
            Matcher m = VERSION_TAG_PATTERN.matcher(response.body());
            while (m.find()) {
                String v = m.group(1).trim();
                if (STABLE_VERSION_PATTERN.matcher(v).matches()) {
                    stableVersions.add(v);
                }
            }

            if (stableVersions.isEmpty()) {
                LOG.warnf("WildflyReleaseClient: no stable WildFly versions found in maven-metadata.xml");
                return Optional.empty();
            }

            String latest = stableVersions.stream()
                    .max(WildflyReleaseClient::compareVersions)
                    .orElseThrow();

            return cacheAndReturn(latest);

        } catch (Exception e) {
            LOG.errorf("WildflyReleaseClient: failed to fetch WildFly version: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Compares two WildFly version strings (e.g. {@code "32.0.1.Final"}) by their numeric
     * segments only, ignoring the {@code .Final} qualifier.
     */
    static int compareVersions(String a, String b) {
        String numA = a.replace(".Final", "").replace(".Alpha1", "").replace(".Beta1", "");
        String numB = b.replace(".Final", "").replace(".Alpha1", "").replace(".Beta1", "");
        String[] partsA = numA.split("\\.");
        String[] partsB = numB.split("\\.");
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
        LOG.infof("WildflyReleaseClient: latest stable WildFly version is %s (cached for %d min)",
                version, cacheDurationMinutes);
        return Optional.of(version);
    }
}
