package com.eneve.agent.tools;

import com.eneve.agent.settings.SettingsService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared HTML-to-text extraction and URL security validation logic,
 * used by both {@link FetchUrlTool} and the web docs crawler.
 *
 * Security controls (enforced by {@link #validateUrl}):
 * - Only HTTPS URLs are accepted.
 * - Private/loopback IP ranges are blocked to prevent SSRF.
 * - Optional domain allowlist ({@code tools.fetch-url.allowed-domains}) restricts fetches.
 */
public final class HtmlTextExtractor {

    private HtmlTextExtractor() {}

    /**
     * Parses {@code html} and returns plain text, preferring main content areas
     * and stripping navigation, sidebars, footers, and scripts.
     *
     * @param html    raw HTML string
     * @param baseUrl base URL used to resolve relative links in the document
     * @return extracted plain text, trimmed and with excessive blank lines collapsed
     */
    public static String extractText(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);

        Elements unwanted = doc.select(
                "script, style, nav, footer, header, aside, "
                + ".nav, .navigation, .sidebar, .menu, .cookie-banner, .ads, .advertisement");
        unwanted.remove();

        Element mainContent = doc.selectFirst("main, article, [role=main], .content, #content, .main");
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
     * Returns an error message if the URL is invalid or disallowed; {@code null} if it is safe.
     * Enforces HTTPS-only, blocks private/loopback IP ranges (SSRF prevention), and
     * optionally respects {@code tools.fetch-url.allowed-domains}.
     *
     * @param url      URL to validate
     * @param settings {@link SettingsService} used to read the allowed-domains list
     * @return error description, or {@code null} if the URL passes all checks
     */
    public static String validateUrl(String url, SettingsService settings) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "Invalid URL: " + e.getMessage();
        }

        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            return "Only HTTPS URLs are allowed (got scheme '" + scheme + "')";
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "URL has no host";
        }

        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                return "Requests to private/internal addresses are not allowed";
            }
        } catch (java.net.UnknownHostException ignored) {
            // Unknown hosts are allowed — DNS resolution will fail at fetch time
        }

        if (settings != null) {
            String allowedDomainsRaw = settings.get("tools.fetch-url.allowed-domains", "");
            List<String> domains = allowedDomainsRaw.isBlank() ? List.of()
                    : Arrays.stream(allowedDomainsRaw.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
            if (!domains.isEmpty()) {
                String normalizedHost = host.toLowerCase();
                boolean permitted = domains.stream()
                        .map(String::toLowerCase)
                        .map(String::strip)
                        .filter(d -> !d.isBlank())
                        .anyMatch(d -> normalizedHost.equals(d) || normalizedHost.endsWith("." + d));
                if (!permitted) {
                    return "Domain '" + host + "' is not in the allowed-domains list. "
                            + "Add it to tools.fetch-url.allowed-domains to permit fetches from this host.";
                }
            }
        }

        return null;
    }
}
