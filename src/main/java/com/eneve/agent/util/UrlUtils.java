package com.eneve.agent.util;

import java.net.URI;

/**
 * Utility methods for working with URLs.
 */
public final class UrlUtils {

    private UrlUtils() {}

    /**
     * Removes the userinfo (credentials) component from a URL so that it is safe
     * to store in logs, AI prompts, notifications, or any other observable output.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code https://user:token@bitbucket.org/org/repo.git}
     *       → {@code https://bitbucket.org/org/repo.git}</li>
     *   <li>{@code https://x-token-auth:SECRET@bitbucket.org/org/repo.git}
     *       → {@code https://bitbucket.org/org/repo.git}</li>
     *   <li>{@code https://bitbucket.org/org/repo.git} → unchanged</li>
     * </ul>
     *
     * Falls back to a simple regex replacement if the URL cannot be parsed as a URI.
     */
    public static String stripCredentials(String url) {
        if (url == null || url.isBlank()) return url;
        try {
            URI uri = new URI(url.trim());
            if (uri.getUserInfo() == null) return url;
            return new URI(
                    uri.getScheme(),
                    null,           // drop userinfo
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
            ).toString();
        } catch (Exception e) {
            // Fallback: strip everything between "://" and the first "@"
            return url.replaceAll("://[^@]+@", "://");
        }
    }
}
