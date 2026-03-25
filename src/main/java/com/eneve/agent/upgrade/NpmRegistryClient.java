package com.eneve.agent.upgrade;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Fetches the latest stable version of an npm package from the npm registry.
 *
 * <p>Calls {@code https://registry.npmjs.org/{package}/latest} which returns the full metadata
 * for the current {@code latest} dist-tag, including a top-level {@code "version"} field.
 * Scoped packages (e.g. {@code @angular/core}) are supported via URL-encoding.
 *
 * <p>Results are cached per-package for a configurable duration to avoid repeated outbound calls.
 */
@ApplicationScoped
public class NpmRegistryClient {

    private static final Logger LOG = Logger.getLogger(NpmRegistryClient.class);

    private static final String NPM_REGISTRY_BASE = "https://registry.npmjs.org/";

    @Inject SettingsService settings;

    private final Map<String, String> versionCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> expiryCache = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Returns the latest stable version of the given npm package (e.g. {@code "react"},
     * {@code "@angular/core"}), using a cached result when available.
     *
     * @param packageName the npm package name; scoped packages (starting with {@code @}) are
     *                    supported
     * @return latest version string (e.g. {@code "18.3.1"}), or empty on failure
     */
    public Optional<String> getLatestVersion(String packageName) {
        String cached = versionCache.get(packageName);
        Instant expiry = expiryCache.get(packageName);
        if (cached != null && expiry != null && Instant.now().isBefore(expiry)) {
            LOG.debugf("NpmRegistryClient: returning cached version %s for %s", cached, packageName);
            return Optional.of(cached);
        }

        try {
            // Scoped packages: "@angular/core" → "@angular%2Fcore"
            String encodedPackage = packageName.startsWith("@")
                    ? packageName.replace("/", "%2F")
                    : packageName;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NPM_REGISTRY_BASE + encodedPackage + "/latest"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("NpmRegistryClient: registry returned HTTP %d for %s",
                        response.statusCode(), packageName);
                return Optional.empty();
            }

            JsonNode root = new ObjectMapper().readTree(response.body());
            JsonNode versionNode = root.get("version");
            if (versionNode == null || versionNode.isNull()) {
                LOG.warnf("NpmRegistryClient: 'version' field missing for package %s", packageName);
                return Optional.empty();
            }

            String version = versionNode.asText().trim();
            return cacheAndReturn(packageName, version);

        } catch (Exception e) {
            LOG.errorf("NpmRegistryClient: failed to fetch version for %s: %s",
                    packageName, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> cacheAndReturn(String packageName, String version) {
        versionCache.put(packageName, version);
        long cacheDurationMinutes = Long.parseLong(settings.get("upgrade.scheduler.version-cache-minutes", "60"));
        expiryCache.put(packageName, Instant.now().plusSeconds(cacheDurationMinutes * 60));
        LOG.infof("NpmRegistryClient: latest version of %s is %s (cached for %d min)",
                packageName, version, cacheDurationMinutes);
        return Optional.of(version);
    }
}
