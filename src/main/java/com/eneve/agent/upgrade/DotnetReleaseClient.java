package com.eneve.agent.upgrade;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Fetches the latest active LTS .NET channel version from the Microsoft release-metadata index.
 *
 * <p>Calls {@code https://dotnetcli.azureedge.net/dotnet/release-metadata/releases-index.json}
 * and selects the highest-numbered channel that is both {@code release-type = "lts"} and
 * {@code support-phase = "active"}, returning its {@code channel-version} value
 * (e.g. {@code "8.0"}).
 *
 * <p>The channel version (major.minor) is returned rather than the full patch release so that
 * it aligns with the {@code <TargetFramework>} value stored by {@code ArchetypeDetector}
 * (e.g. {@code "net8.0"} → normalized {@code "8.0"}).  A repo is only flagged for upgrade
 * when a newer LTS channel is available (e.g. 8.0 → 10.0), not for patch updates within
 * the same channel.
 *
 * <p>Results are cached for a configurable duration (shared with the upgrade scheduler
 * setting) to avoid repeated outbound calls during a single scheduler run.
 */
@ApplicationScoped
public class DotnetReleaseClient {

    private static final Logger LOG = Logger.getLogger(DotnetReleaseClient.class);

    private static final String RELEASES_INDEX_URL =
            "https://dotnetcli.azureedge.net/dotnet/release-metadata/releases-index.json";

    @Inject SettingsService settings;

    private volatile String cachedVersion;
    private volatile Instant cacheExpiry = Instant.EPOCH;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Returns the highest active LTS .NET channel version, using a cached result when available.
     *
     * <p>Selects only channels with {@code release-type = "lts"} and
     * {@code support-phase = "active"}, then picks the one with the highest
     * {@code channel-version}.
     *
     * @return active LTS channel version string (e.g. {@code "8.0"}), or empty on failure
     */
    public Optional<String> getLatestDotnetVersion() {
        if (cachedVersion != null && Instant.now().isBefore(cacheExpiry)) {
            LOG.debugf("DotnetReleaseClient: returning cached .NET version %s", cachedVersion);
            return Optional.of(cachedVersion);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASES_INDEX_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("DotnetReleaseClient: releases-index.json returned HTTP %d",
                        response.statusCode());
                return Optional.empty();
            }

            JsonNode root = new ObjectMapper().readTree(response.body());
            JsonNode index = root.get("releases-index");
            if (index == null || !index.isArray()) {
                LOG.warnf("DotnetReleaseClient: unexpected JSON structure — 'releases-index' array missing");
                return Optional.empty();
            }

            String bestChannelVersion = null;
            String bestLatestRelease  = null;

            for (JsonNode channel : index) {
                String releaseType   = textField(channel, "release-type");
                String supportPhase  = textField(channel, "support-phase");
                String channelVersion = textField(channel, "channel-version");
                String latestRelease  = textField(channel, "latest-release");

                if (!"lts".equalsIgnoreCase(releaseType) || !"active".equalsIgnoreCase(supportPhase)) {
                    continue;
                }
                if (channelVersion == null || latestRelease == null) {
                    continue;
                }

                if (bestChannelVersion == null
                        || compareVersions(channelVersion, bestChannelVersion) > 0) {
                    bestChannelVersion = channelVersion;
                    bestLatestRelease  = latestRelease;
                }
            }

            if (bestChannelVersion == null) {
                LOG.warnf("DotnetReleaseClient: no active LTS channel found in releases-index");
                return Optional.empty();
            }

            return cacheAndReturn(bestChannelVersion);

        } catch (Exception e) {
            LOG.errorf("DotnetReleaseClient: failed to fetch .NET version: %s", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static String textField(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull()) ? n.asText().trim() : null;
    }

    /**
     * Compares two version strings semantically by splitting on {@code '.'} and comparing
     * each numeric segment left-to-right.  Non-numeric segments are compared as strings.
     */
    static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            String segA = i < partsA.length ? partsA[i] : "0";
            String segB = i < partsB.length ? partsB[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(segA), Integer.parseInt(segB));
            } catch (NumberFormatException e) {
                cmp = segA.compareTo(segB);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private Optional<String> cacheAndReturn(String version) {
        cachedVersion = version;
        long cacheDurationMinutes = Long.parseLong(settings.get("upgrade.scheduler.version-cache-minutes", "60"));
        cacheExpiry = Instant.now().plusSeconds(cacheDurationMinutes * 60);
        LOG.infof("DotnetReleaseClient: latest active LTS .NET channel version is %s (cached for %d min)",
                version, cacheDurationMinutes);
        return Optional.of(version);
    }
}
