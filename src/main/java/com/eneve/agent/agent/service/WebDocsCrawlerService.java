package com.eneve.agent.agent.service;

import com.eneve.agent.agent.model.WebDocSource;
import com.eneve.agent.security.SsrfGuard;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.tools.HtmlTextExtractor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * BFS web crawler that fetches and extracts text from documentation pages.
 *
 * <p>For each configured {@link WebDocSource} it:
 * <ol>
 *   <li>Optionally discovers URLs via {@code sitemap.xml}.</li>
 *   <li>Follows links using BFS, staying within {@code allowedPathPrefix}.</li>
 *   <li>Respects {@code robots.txt} for the host.</li>
 *   <li>Applies a configurable delay between requests.</li>
 *   <li>Returns a list of {@link WebPage} records for the caller to embed.</li>
 * </ol>
 *
 * <p>Security: URL validation (HTTPS-only, SSRF guard) is delegated to
 * {@link HtmlTextExtractor#validateUrl(String, SettingsService)} with {@code null} settings,
 * so the {@code tools.fetch-url.allowed-domains} allowlist is intentionally bypassed —
 * web doc sources are explicitly registered by admins and are therefore already trusted.
 */
@ApplicationScoped
public class WebDocsCrawlerService {

    private static final Logger LOG = Logger.getLogger(WebDocsCrawlerService.class);

    @Inject
    SettingsService settingsService;

    /**
     * Crawl a single documentation source and return all successfully fetched pages.
     *
     * @param source the configured web doc source
     * @return list of fetched pages; never null
     */
    public List<WebPage> crawl(WebDocSource source) {
        int connectTimeoutMs = Integer.parseInt(
                settingsService.get("knowledge.crawler.connect-timeout-ms", "5000"));
        String userAgent = settingsService.get("knowledge.crawler.user-agent", "code-agent-bot/1.0");
        int globalMaxPages = Integer.parseInt(
                settingsService.get("knowledge.crawler.global-max-pages", "2000"));

        int effectiveMaxPages = Math.min(source.maxPages(), globalMaxPages);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        Set<String> disallowedPaths = fetchRobotsTxtDisallowed(source.baseUrl(), client, userAgent, connectTimeoutMs);
        List<String> sitemapUrls = fetchSitemapUrls(source.baseUrl(), source.allowedPathPrefix(), client, userAgent, connectTimeoutMs);

        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();

        if (!sitemapUrls.isEmpty()) {
            LOG.infof("WebDocsCrawler: using sitemap.xml for %s (%d URLs)", source.baseUrl(), sitemapUrls.size());
            sitemapUrls.forEach(queue::add);
        } else {
            queue.add(source.baseUrl());
        }

        List<WebPage> pages = new ArrayList<>();

        while (!queue.isEmpty() && visited.size() < effectiveMaxPages) {
            String url = queue.poll();
            String normalized = normalizeUrl(url);
            if (normalized == null || visited.contains(normalized)) continue;
            if (!normalized.startsWith(source.allowedPathPrefix())) continue;
            if (isDisallowed(normalized, disallowedPaths)) {
                LOG.debugf("WebDocsCrawler: robots.txt disallows %s", normalized);
                continue;
            }

            // Pass null for settings so the tools.fetch-url.allowed-domains allowlist is not
            // applied — web doc sources are explicitly registered by admins and are trusted.
            // SSRF protection (HTTPS-only, private IP block) still runs.
            String error = HtmlTextExtractor.validateUrl(normalized, null);
            if (error != null) {
                LOG.debugf("WebDocsCrawler: skipping %s — %s", normalized, error);
                visited.add(normalized);
                continue;
            }

            visited.add(normalized);

            try {
                if (source.crawlDelayMs() > 0) {
                    Thread.sleep(source.crawlDelayMs());
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(normalized))
                        .timeout(Duration.ofMillis(connectTimeoutMs))
                        .header("User-Agent", userAgent)
                        .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    LOG.debugf("WebDocsCrawler: HTTP %d for %s", status, normalized);
                    continue;
                }

                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.contains("text/html") && !contentType.isBlank()) {
                    continue;
                }

                String html = response.body();
                String text = HtmlTextExtractor.extractText(html, normalized);
                String title = extractTitle(html, normalized);

                if (!text.isBlank()) {
                    pages.add(new WebPage(normalized, title, text));
                }

                // Enqueue discovered links (only if URLs came from link-following, not sitemap)
                if (sitemapUrls.isEmpty()) {
                    collectLinks(html, normalized, source.allowedPathPrefix(), visited, queue);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warnf("WebDocsCrawler: interrupted while crawling %s", source.baseUrl());
                break;
            } catch (Exception e) {
                LOG.warnf("WebDocsCrawler: failed to fetch %s: %s", normalized, e.getMessage());
            }
        }

        LOG.infof("WebDocsCrawler: crawled %d pages for source %s", pages.size(), source.baseUrl());
        return pages;
    }

    // ── Sitemap ───────────────────────────────────────────────────────────────

    private List<String> fetchSitemapUrls(String baseUrl, String allowedPrefix,
                                           HttpClient client, String userAgent, int timeoutMs) {
        String sitemapUrl = baseUrl.replaceAll("/+$", "") + "/sitemap.xml";
        String ssrfError = SsrfGuard.validateSameHost(baseUrl, sitemapUrl);
        if (ssrfError != null) {
            LOG.debugf("WebDocsCrawler: sitemap.xml fetch blocked (SSRF): %s — %s", sitemapUrl, ssrfError);
            return List.of();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(sitemapUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return List.of();

            Document doc = Jsoup.parse(resp.body(), sitemapUrl);
            List<String> urls = new ArrayList<>();
            for (Element loc : doc.select("loc")) {
                String url = loc.text().trim();
                if (url.startsWith(allowedPrefix)) {
                    urls.add(url);
                }
            }
            return urls;
        } catch (Exception e) {
            LOG.debugf("WebDocsCrawler: no sitemap.xml at %s: %s", sitemapUrl, e.getMessage());
            return List.of();
        }
    }

    // ── robots.txt ────────────────────────────────────────────────────────────

    private Set<String> fetchRobotsTxtDisallowed(String baseUrl, HttpClient client,
                                                   String userAgent, int timeoutMs) {
        try {
            URI base = URI.create(baseUrl);
            String robotsUrl = base.getScheme() + "://" + base.getHost()
                    + (base.getPort() > 0 ? ":" + base.getPort() : "") + "/robots.txt";
            String ssrfError = SsrfGuard.validateSameHost(baseUrl, robotsUrl);
            if (ssrfError != null) {
                LOG.debugf("WebDocsCrawler: robots.txt fetch blocked (SSRF): %s — %s", robotsUrl, ssrfError);
                return Set.of();
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(robotsUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Set.of();

            Set<String> disallowed = new HashSet<>();
            boolean relevantAgent = false;
            for (String line : resp.body().lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.toLowerCase().startsWith("user-agent:")) {
                    String agent = trimmed.substring("user-agent:".length()).trim();
                    relevantAgent = agent.equals("*") || userAgent.toLowerCase().contains(agent.toLowerCase());
                } else if (relevantAgent && trimmed.toLowerCase().startsWith("disallow:")) {
                    String path = trimmed.substring("disallow:".length()).trim();
                    if (!path.isEmpty()) disallowed.add(path);
                }
            }
            return disallowed;
        } catch (Exception e) {
            LOG.debugf("WebDocsCrawler: could not fetch robots.txt for %s: %s", baseUrl, e.getMessage());
            return Set.of();
        }
    }

    private boolean isDisallowed(String url, Set<String> disallowedPaths) {
        try {
            String path = URI.create(url).getPath();
            return disallowedPaths.stream().anyMatch(d -> !d.isEmpty() && path.startsWith(d));
        } catch (Exception e) {
            return false;
        }
    }

    // ── Link discovery ────────────────────────────────────────────────────────

    private void collectLinks(String html, String baseUrl, String allowedPrefix,
                               Set<String> visited, Queue<String> queue) {
        Document doc = Jsoup.parse(html, baseUrl);
        Elements anchors = doc.select("a[href]");
        for (Element a : anchors) {
            String href = a.absUrl("href");
            String normalized = normalizeUrl(href);
            if (normalized != null
                    && normalized.startsWith(allowedPrefix)
                    && !visited.contains(normalized)) {
                queue.add(normalized);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = new URI(url).normalize();
            // Strip fragment
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
                    uri.getQuery(), null).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractTitle(String html, String fallback) {
        try {
            Document doc = Jsoup.parse(html);
            String title = doc.title();
            return title != null && !title.isBlank() ? title : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * A single fetched documentation page.
     *
     * @param url         canonical URL of the page
     * @param title       page title (from {@code <title>} tag)
     * @param textContent plain-text content extracted by {@link HtmlTextExtractor}
     */
    public record WebPage(String url, String title, String textContent) {}
}
