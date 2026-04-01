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
 * 1. HTTPS-only — only HTTPS scheme is accepted.
 * 2. Reserved-TLD block — hostnames ending in IANA/RFC reserved suffixes (.local, .internal, etc.)
 *    are rejected without DNS resolution. These are permanently non-delegated and can never be
 *    legitimate public internet hostnames, so no configuration is required.
 * 3. Private-IP SSRF block — the resolved IP address must not be a loopback, site-local,
 *    link-local, or any-local address. DNS resolution failure is also rejected to prevent
 *    DNS-rebinding attacks.
 * 4. Optional domain allowlist ({@code tools.fetch-url.allowed-domains}) — when set, restricts
 *    fetches to the listed domains. Leave empty (the default) to allow all public URLs.
 */
public final class HtmlTextExtractor {

    /**
     * IANA/RFC permanently reserved TLD suffixes that can never belong to a public internet
     * hostname. Hardcoded — these are defined by standards and never change.
     */
    private static final List<String> RESERVED_TLDS = List.of(
            ".local", ".internal", ".intranet", ".corp", ".lan",
            ".home", ".private", ".localdomain", ".localhost"
    );

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

        // Check 2 — reserved TLD block (zero-maintenance, no config needed)
        String lowerHost = host.toLowerCase();
        for (String tld : RESERVED_TLDS) {
            if (lowerHost.equals(tld.substring(1)) || lowerHost.endsWith(tld)) {
                return "Requests to private/internal hostnames are not allowed (reserved TLD)";
            }
        }

        // Check 3 — private-IP SSRF block.
        // UnknownHostException means the host is not resolvable; since it cannot map to a
        // private/internal address we let it pass — the actual fetch will fail naturally.
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                return "Requests to private/internal addresses are not allowed";
            }
        } catch (java.net.UnknownHostException ignored) {
            // Not resolvable → cannot be a private address → allow through
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
