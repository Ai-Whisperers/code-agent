package com.eneve.agent.scm.bitbucket;

import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Manages OAuth 2.0 Client Credentials tokens for the Bitbucket bot account.
 *
 * <p>Set up a Bitbucket OAuth Consumer in your Workspace Settings
 * (Workspace → Settings → OAuth consumers → Add consumer). Configure:
 * <ul>
 *   <li>{@code bitbucket.oauth.client-id}   — consumer Key</li>
 *   <li>{@code bitbucket.oauth.client-secret} — consumer Secret</li>
 * </ul>
 *
 * <p>The consumer needs the following permission scopes:
 * <ul>
 *   <li>Account: Read (so {@code GET /user} works for identity resolution)</li>
 *   <li>Repositories: Read + Write (for diff, comments, PR creation/merge)</li>
 *   <li>Pull Requests: Read + Write</li>
 * </ul>
 *
 * <p>Tokens are cached and automatically refreshed 60 seconds before expiry.
 * When OAuth credentials are not configured, the service returns {@code null}
 * and {@link BitbucketPlatformService} falls back to App Password auth.
 */
@ApplicationScoped
public class BitbucketTokenManager {

    private static final Logger LOG = Logger.getLogger(BitbucketTokenManager.class);
    private static final String TOKEN_URL = "https://bitbucket.org/site/oauth2/access_token";
    private static final int EXPIRY_BUFFER_SECONDS = 60;

    @Inject
    SettingsService settings;

    @Inject HttpClient httpClient;
    @Inject ObjectMapper objectMapper;

    private volatile String accessToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    /**
     * Returns a valid Bearer token, refreshing it if it has expired or is about to.
     * Returns {@code null} if OAuth credentials are not configured — callers should
     * fall back to App Password authentication in that case.
     */
    public String getAccessToken() {
        if (!isConfigured()) {
            return null;
        }
        if (accessToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(EXPIRY_BUFFER_SECONDS))) {
            return accessToken;
        }
        return refreshToken();
    }

    /** Returns {@code true} if both client-id and client-secret are configured. */
    public boolean isConfigured() {
        String clientId = settings.get("bitbucket.oauth.client-id", "");
        String clientSecret = settings.getSecret("bitbucket.oauth.client-secret");
        return !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    private synchronized String refreshToken() {
        // Double-check after acquiring the lock
        if (accessToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(EXPIRY_BUFFER_SECONDS))) {
            return accessToken;
        }

        String clientId = settings.get("bitbucket.oauth.client-id", "");
        String clientSecret = settings.getSecret("bitbucket.oauth.client-secret");

        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(TOKEN_URL))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.errorf("Bitbucket OAuth token request failed (HTTP %d): %s",
                        response.statusCode(), response.body());
                accessToken = null;
                return null;
            }

            JsonNode node = objectMapper.readTree(response.body());
            String token = node.path("access_token").asText("");
            int expiresIn = node.path("expires_in").asInt(7200);

            if (token.isBlank()) {
                LOG.errorf("Bitbucket OAuth token response contained no access_token: %s", response.body());
                accessToken = null;
                return null;
            }

            accessToken = token;
            tokenExpiry = Instant.now().plusSeconds(expiresIn);
            LOG.infof("Acquired Bitbucket OAuth token (expires in %ds)", expiresIn);
            return accessToken;

        } catch (Exception e) {
            LOG.errorf("Bitbucket OAuth token refresh failed: %s", e.getMessage());
            accessToken = null;
            return null;
        }
    }
}
