package com.eneve.agent.security;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/**
 * SSRF (Server-Side Request Forgery) protection utilities.
 *
 * <p>Two validation modes are provided:
 * <ul>
 *   <li>{@link #validatePublicUrl} — full SSRF check for URLs that may come from untrusted input.
 *       Enforces HTTPS-only, blocks reserved TLDs, and blocks private/loopback IP ranges.</li>
 *   <li>{@link #validateSameHost} — lightweight check for URLs constructed by appending a
 *       server-controlled path to an admin-configured base URL. Ensures the final URL stays
 *       on the same host as the configured base, preventing host-override injection.</li>
 * </ul>
 *
 * <p>Both {@code validate*} methods return {@code null} on success or a human-readable error
 * string on failure. Callers must treat a non-null return as a hard rejection.
 *
 * <p>The {@code safeUri*} factory methods combine validation and URI construction in a single
 * call, throwing {@link IllegalArgumentException} if the URL fails any check. Use these
 * directly in {@code HttpRequest.newBuilder().uri(...)} chains so that no unvalidated URI
 * variable ever reaches the HTTP client.
 */
public final class SsrfGuard {

    /**
     * IANA/RFC permanently reserved TLD suffixes that can never belong to a public internet
     * hostname. Hardcoded — these are defined by standards and never change.
     */
    private static final List<String> RESERVED_TLDS = List.of(
            ".local", ".internal", ".intranet", ".corp", ".lan",
            ".home", ".private", ".localdomain", ".localhost"
    );

    private SsrfGuard() {}

    /**
     * Full SSRF validation for URLs that may originate from untrusted or semi-trusted input
     * (e.g. webhook URLs supplied by users, OAuth token endpoints, attachment download URLs).
     *
     * <p>Checks performed:
     * <ol>
     *   <li>URL must be parseable.</li>
     *   <li>Scheme must be {@code https}.</li>
     *   <li>Host must not end with a reserved/private TLD.</li>
     *   <li>Resolved IP address must not be loopback, site-local, link-local, or any-local.
     *       DNS resolution failure is treated as safe (the fetch will fail naturally).</li>
     * </ol>
     *
     * @param url the URL to validate
     * @return {@code null} if the URL is safe; a non-null error message if it is rejected
     */
    public static String validatePublicUrl(String url) {
        if (url == null || url.isBlank()) {
            return "URL must not be blank";
        }

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

        String lowerHost = host.toLowerCase();
        for (String tld : RESERVED_TLDS) {
            if (lowerHost.equals(tld.substring(1)) || lowerHost.endsWith(tld)) {
                return "Requests to private/internal hostnames are not allowed (reserved TLD '" + tld + "')";
            }
        }

        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                return "Requests to private/internal IP addresses are not allowed";
            }
        } catch (java.net.UnknownHostException ignored) {
            // Not resolvable → cannot be a private address → allow through
        }

        return null;
    }

    /**
     * Validates {@code url} with {@link #validatePublicUrl} and returns the parsed {@link URI}.
     * Throws {@link IllegalArgumentException} if validation fails, so the URI can be used
     * directly in an {@code HttpRequest.newBuilder().uri(...)} chain without an intermediate
     * unvalidated variable.
     *
     * @param url the URL to validate and parse
     * @return a validated {@link URI}
     * @throws IllegalArgumentException if the URL is rejected by SSRF checks
     */
    public static URI safePublicUri(String url) {
        String error = validatePublicUrl(url);
        if (error != null) {
            throw new IllegalArgumentException("SSRF check failed for '" + url + "': " + error);
        }
        return URI.create(url);
    }

    /**
     * Validates that {@code fullUrl} stays on the same host as {@code configuredBaseUrl} (via
     * {@link #validateSameHost}) and returns the parsed {@link URI}. Throws
     * {@link IllegalArgumentException} if validation fails.
     *
     * @param configuredBaseUrl the trusted base URL from application settings
     * @param fullUrl           the fully-assembled URL about to be requested
     * @return a validated {@link URI}
     * @throws IllegalArgumentException if the URL is rejected by SSRF checks
     */
    public static URI safeSameHostUri(String configuredBaseUrl, String fullUrl) {
        String error = validateSameHost(configuredBaseUrl, fullUrl);
        if (error != null) {
            throw new IllegalArgumentException("SSRF check failed for '" + fullUrl + "': " + error);
        }
        return URI.create(fullUrl);
    }

    /**
     * Validates that a fully-constructed URL stays on the same host as the admin-configured
     * base URL. Use this when the base URL is trusted (from settings) but the final URL is
     * assembled by concatenating a path that could theoretically be manipulated.
     *
     * <p>Also applies the full public-URL checks so that misconfigured base URLs (e.g. pointing
     * to an internal address) are caught at call time rather than silently allowed.
     *
     * @param configuredBaseUrl the trusted base URL from application settings
     * @param fullUrl           the fully-assembled URL about to be requested
     * @return {@code null} if the URL is safe; a non-null error message if it is rejected
     */
    public static String validateSameHost(String configuredBaseUrl, String fullUrl) {
        String publicError = validatePublicUrl(fullUrl);
        if (publicError != null) {
            return publicError;
        }

        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            return "Base URL is not configured";
        }

        try {
            String expectedHost = URI.create(configuredBaseUrl).getHost();
            String actualHost   = URI.create(fullUrl).getHost();
            if (expectedHost == null || !expectedHost.equalsIgnoreCase(actualHost)) {
                return "URL host '" + actualHost + "' does not match the configured host '" + expectedHost + "'";
            }
        } catch (IllegalArgumentException e) {
            return "Invalid URL: " + e.getMessage();
        }

        return null;
    }
}
