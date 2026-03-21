package com.eneve.agent.upgrade;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Fetches the official Quarkus migration guide for a given target version and returns
 * its text content to be embedded into the upgrade spec sent to the AI planner.
 *
 * <p>Quarkus publishes migration guides at:
 * {@code https://quarkus.io/guides/migration-guide-{major.minor}}
 *
 * <p>Failure is non-fatal: if the guide cannot be retrieved a fallback message pointing
 * to the URL is returned instead, so the upgrade plan is still created.
 */
@ApplicationScoped
public class QuarkusMigrationFetcher {

    private static final Logger LOG = Logger.getLogger(QuarkusMigrationFetcher.class);
    private static final int MAX_CHARS = 15_000;
    private static final String BASE_URL = "https://quarkus.io/guides/migration-guide-";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Fetches and extracts migration guide text for the given target Quarkus version.
     *
     * @param toVersion target version string (e.g. {@code "3.17.0"})
     * @return extracted migration guide text (truncated to 15 000 chars), or a fallback message
     */
    public Optional<String> fetchMigrationNotes(String toVersion) {
        if (toVersion == null || toVersion.isBlank()) {
            return Optional.empty();
        }

        String majorMinor = extractMajorMinor(toVersion);
        String url = BASE_URL + majorMinor;

        LOG.infof("QuarkusMigrationFetcher: fetching migration guide from %s", url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "code-agent/1.0 (upgrade-automation)")
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                LOG.infof("QuarkusMigrationFetcher: no migration guide found at %s (404)", url);
                return Optional.of(fallbackMessage(url));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("QuarkusMigrationFetcher: HTTP %d fetching %s", response.statusCode(), url);
                return Optional.of(fallbackMessage(url));
            }

            String text = extractText(response.body(), url);
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS)
                        + "\n\n... [migration guide truncated at " + MAX_CHARS + " characters. "
                        + "Full guide: " + url + "]";
            }

            LOG.infof("QuarkusMigrationFetcher: fetched %d chars for migration guide %s",
                    text.length(), majorMinor);
            return Optional.of(text);

        } catch (Exception e) {
            LOG.warnf("QuarkusMigrationFetcher: failed to fetch migration guide from %s: %s",
                    url, e.getMessage());
            return Optional.of(fallbackMessage(url));
        }
    }

    private static String extractText(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);

        // Remove non-content elements
        Elements unwanted = doc.select("script, style, nav, footer, header, aside, "
                + ".nav, .navigation, .sidebar, .menu, .cookie-banner");
        unwanted.remove();

        // Prefer main content area
        Element mainContent = doc.selectFirst("main, article, [role=main], .content, #content");
        String text;
        if (mainContent != null) {
            text = mainContent.wholeText();
        } else {
            Element body = doc.body();
            text = body != null ? body.wholeText() : doc.wholeText();
        }

        return text.replaceAll("(?m)^[ \\t]+$", "")
                   .replaceAll("\\n{3,}", "\n\n")
                   .strip();
    }

    /**
     * Extracts the {@code major.minor} part from a version string.
     * e.g. {@code "3.17.0"} → {@code "3.17"}, {@code "3.17"} → {@code "3.17"}
     */
    static String extractMajorMinor(String version) {
        String[] parts = version.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return version;
    }

    private static String fallbackMessage(String url) {
        return "No migration guide could be fetched automatically. "
                + "Please consult the official Quarkus migration guide at: " + url;
    }
}
