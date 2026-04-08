package com.eneve.agent.upgrade;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Fetches the latest stable version of a Packagist (PHP Composer) package.
 *
 * <p>Calls {@code https://packagist.org/packages/{vendor}/{package}.json} and filters the
 * {@code versions} map for stable releases matching {@code <major>.<minor>.<patch>}
 * (with or without a leading {@code v} prefix).
 *
 * <p>Results are cached per-package for a configurable duration to avoid repeated outbound calls.
 */
@ApplicationScoped
public class PackagistClient {

    private static final Logger LOG = Logger.getLogger(PackagistClient.class);

    private static final String PACKAGIST_BASE = "https://packagist.org/packages/";

    /** Matches stable versions like {@code 11.30.0} or {@code v11.30.0} — no qualifiers. */
    private static final Pattern STABLE_VERSION_PATTERN =
            Pattern.compile("^v?\\d+\\.\\d+\\.\\d+$");

    @Inject SettingsService settings;
    @Inject ObjectMapper objectMapper;

    private final Map<String, String> versionCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> expiryCache = new ConcurrentHashMap<>();

    @Inject HttpClient httpClient;

    /**
     * Returns the latest stable version for a Packagist package, using a cached result when
     * available.
     *
     * @param vendor  the Packagist vendor name (e.g. {@code "laravel"})
     * @param pkg     the package name (e.g. {@code "framework"})
     * @return latest stable version string without leading {@code v} (e.g. {@code "11.30.0"}),
     *         or empty on failure
     */
    public Optional<String> getLatestVersion(String vendor, String pkg) {
        String key = vendor + "/" + pkg;
        String cached = versionCache.get(key);
        Instant expiry = expiryCache.get(key);
        if (cached != null && expiry != null && Instant.now().isBefore(expiry)) {
            LOG.debugf("PackagistClient: returning cached version %s for %s", cached, key);
            return Optional.of(cached);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PACKAGIST_BASE + key + ".json"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("PackagistClient: Packagist returned HTTP %d for %s",
                        response.statusCode(), key);
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode versions = root.path("package").path("versions");
            if (versions.isMissingNode() || !versions.isObject()) {
                LOG.warnf("PackagistClient: unexpected JSON structure for %s — 'versions' not found", key);
                return Optional.empty();
            }

            List<String> stableVersions = new ArrayList<>();
            versions.fieldNames().forEachRemaining(v -> {
                if (STABLE_VERSION_PATTERN.matcher(v).matches()) {
                    // Strip leading 'v' for consistent comparisons
                    stableVersions.add(v.startsWith("v") ? v.substring(1) : v);
                }
            });

            if (stableVersions.isEmpty()) {
                LOG.warnf("PackagistClient: no stable versions found for %s", key);
                return Optional.empty();
            }

            String latest = stableVersions.stream()
                    .max(MavenCentralClient::compareVersions)
                    .orElseThrow();

            return cacheAndReturn(key, latest);

        } catch (Exception e) {
            LOG.errorf("PackagistClient: failed to fetch version for %s: %s", key, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> cacheAndReturn(String key, String version) {
        versionCache.put(key, version);
        long cacheDurationMinutes = Long.parseLong(settings.get("upgrade.scheduler.version-cache-minutes", "60"));
        expiryCache.put(key, Instant.now().plusSeconds(cacheDurationMinutes * 60));
        LOG.infof("PackagistClient: latest stable version of %s is %s (cached for %d min)",
                key, version, cacheDurationMinutes);
        return Optional.of(version);
    }
}
