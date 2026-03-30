package com.eneve.agent.mcp;

import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the Atlassian OAuth 2.0 3-legged (3LO) flow for linking user Jira accounts.
 *
 * <p>Flow:
 * <ol>
 *   <li>Frontend calls {@link #generateAuthorizationUrl} → opens returned URL in popup</li>
 *   <li>User authorises in Atlassian, which redirects back to the backend callback</li>
 *   <li>Backend calls {@link #handleCallback} → exchanges code for tokens, stores linked account</li>
 * </ol>
 *
 * <p>Required settings:
 * <ul>
 *   <li>{@code atlassian.oauth.client-id} — OAuth 2.0 client ID</li>
 *   <li>{@code atlassian.oauth.client-secret} — OAuth 2.0 client secret</li>
 * </ul>
 */
@ApplicationScoped
public class AtlassianOAuthService {

    private static final Logger LOG = Logger.getLogger(AtlassianOAuthService.class);

    private static final String AUTH_BASE     = "https://auth.atlassian.com";
    private static final String API_BASE      = "https://api.atlassian.com";
    private static final long   STATE_TTL_SEC = 600; // 10 minutes

    /**
     * Scopes requested during the OAuth flow.
     * {@code offline_access} enables refresh tokens.
     */
    private static final String SCOPES =
            "read:jira-work read:jira-user write:jira-work offline_access";

    @Inject
    SettingsService settings;

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject HttpClient http;
    @Inject ObjectMapper mapper;

    // ── Pending OAuth states ────────────────────────────────────────────────────

    private record StateEntry(String userId, Instant expiresAt) {}

    private final ConcurrentHashMap<String, StateEntry> pendingStates = new ConcurrentHashMap<>();

    // ── Public API ──────────────────────────────────────────────────────────────

    public record AuthorizeResult(String url, String state) {}

    /**
     * Generates an Atlassian OAuth 2.0 authorization URL.
     *
     * @param userId      the authenticated user's ID (stored with the state for later lookup)
     * @param redirectUri the exact redirect URI registered in your Atlassian OAuth app
     */
    public AuthorizeResult generateAuthorizationUrl(String userId, String redirectUri) {
        String clientId = settings.get("atlassian.oauth.client-id", "");
        if (clientId.isBlank()) {
            throw new IllegalStateException(
                    "atlassian.oauth.client-id is not configured. " +
                    "Add it in System Settings → Atlassian.");
        }

        String state = UUID.randomUUID().toString();
        pendingStates.put(state, new StateEntry(userId, Instant.now().plusSeconds(STATE_TTL_SEC)));
        // Lazy cleanup of expired entries
        pendingStates.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(Instant.now()));

        String url = AUTH_BASE + "/authorize"
                + "?audience="      + enc("api.atlassian.com")
                + "&client_id="     + enc(clientId)
                + "&scope="         + enc(SCOPES)
                + "&redirect_uri="  + enc(redirectUri)
                + "&state="         + enc(state)
                + "&response_type=code"
                + "&prompt=consent";

        return new AuthorizeResult(url, state);
    }

    /** Whether Atlassian OAuth is configured on this server. */
    public boolean isConfigured() {
        return !settings.get("atlassian.oauth.client-id", "").isBlank();
    }

    public record CallbackResult(boolean success, String provider, String message) {}

    /**
     * Handles the OAuth callback: validates state, exchanges code for tokens,
     * resolves the user's Jira site, and persists the linked account.
     *
     * @param code        authorisation code from Atlassian
     * @param state       CSRF state token
     * @param redirectUri must match the one used in {@link #generateAuthorizationUrl}
     */
    public CallbackResult handleCallback(String code, String state, String redirectUri) {
        StateEntry entry = pendingStates.remove(state);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return new CallbackResult(false, "jira",
                    "Invalid or expired OAuth state. Please try again.");
        }
        String userId = entry.userId();

        try {
            String clientId     = settings.get("atlassian.oauth.client-id", "");
            String clientSecret = settings.get("atlassian.oauth.client-secret", "");
            if (clientId.isBlank() || clientSecret.isBlank()) {
                return new CallbackResult(false, "jira",
                        "Atlassian OAuth credentials are not configured on this server.");
            }

            // ── Exchange authorisation code for tokens ──────────────────────────
            String tokenBody = "grant_type=authorization_code"
                    + "&client_id="     + enc(clientId)
                    + "&client_secret=" + enc(clientSecret)
                    + "&code="          + enc(code)
                    + "&redirect_uri="  + enc(redirectUri);

            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(AUTH_BASE + "/oauth/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
                    .build();

            HttpResponse<String> tokenResp = http.send(tokenReq, HttpResponse.BodyHandlers.ofString());
            if (tokenResp.statusCode() != 200) {
                LOG.warnf("Atlassian token exchange failed (%d): %s",
                        tokenResp.statusCode(), tokenResp.body());
                return new CallbackResult(false, "jira",
                        "Token exchange failed (HTTP " + tokenResp.statusCode() + "). Please try again.");
            }

            JsonNode tokenJson    = mapper.readTree(tokenResp.body());
            String   accessToken  = tokenJson.path("access_token").asText("");
            String   refreshToken = tokenJson.path("refresh_token").asText("");

            if (accessToken.isBlank()) {
                return new CallbackResult(false, "jira", "No access token returned by Atlassian.");
            }

            // ── Retrieve the user's accessible Atlassian cloud sites ────────────
            HttpRequest resourcesReq = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(API_BASE + "/oauth/token/accessible-resources"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> resourcesResp =
                    http.send(resourcesReq, HttpResponse.BodyHandlers.ofString());
            if (resourcesResp.statusCode() != 200) {
                LOG.warnf("Atlassian accessible-resources failed (%d): %s",
                        resourcesResp.statusCode(), resourcesResp.body());
                return new CallbackResult(false, "jira",
                        "Could not retrieve accessible Atlassian sites.");
            }

            JsonNode sites = mapper.readTree(resourcesResp.body());
            if (!sites.isArray() || sites.isEmpty()) {
                return new CallbackResult(false, "jira",
                        "No accessible Atlassian sites found. Make sure this account has Jira access.");
            }

            JsonNode site     = pickBestSite(sites);
            String   cloudId  = site.path("id").asText();
            String   siteName = site.path("name").asText(cloudId);

            // For the Atlassian REST API via OAuth, requests are made to:
            // https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/...
            String jiraBaseUrl = API_BASE + "/ex/jira/" + cloudId;

            // ── Resolve the user's email / account ID from /myself ──────────────
            HttpRequest myselfReq = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(jiraBaseUrl + "/rest/api/3/myself"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> myselfResp =
                    http.send(myselfReq, HttpResponse.BodyHandlers.ofString());
            String username;
            if (myselfResp.statusCode() == 200) {
                JsonNode myself = mapper.readTree(myselfResp.body());
                String email = myself.path("emailAddress").asText("");
                username = email.isBlank() ? myself.path("accountId").asText("oauth-user") : email;
            } else {
                username = "oauth-user";
            }

            // ── Persist the linked account ──────────────────────────────────────
            String displayName = "Atlassian OAuth · " + siteName;
            linkedAccountService.upsertOAuth(
                    userId, "jira", displayName, jiraBaseUrl, username,
                    accessToken, refreshToken.isBlank() ? null : refreshToken);

            LOG.infof("Atlassian OAuth linked for user=%s site=%s (%s)", userId, siteName, cloudId);
            return new CallbackResult(true, "jira",
                    "Connected to " + siteName + " as " + username);

        } catch (Exception e) {
            LOG.errorf(e, "Atlassian OAuth callback failed for user=%s", userId);
            return new CallbackResult(false, "jira", "OAuth error: " + e.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Picks the Atlassian site that best matches the configured {@code jira.base.url},
     * or falls back to the first site if none matches.
     */
    private JsonNode pickBestSite(JsonNode sites) {
        String configuredBase = settings.get("jira.base.url", "");
        if (!configuredBase.isBlank()) {
            try {
                String configuredHost = URI.create(configuredBase).getHost();
                if (configuredHost != null) {
                    for (JsonNode site : sites) {
                        String siteUrl = site.path("url").asText("");
                        if (!siteUrl.isBlank()) {
                            String siteHost = URI.create(siteUrl).getHost();
                            if (siteHost != null && (siteHost.contains(configuredHost)
                                    || configuredHost.contains(siteHost))) {
                                return site;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fall through to default
            }
        }
        return sites.get(0);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ── Token refresh ────────────────────────────────────────────────────────────

    /**
     * Refreshes an expired access token using the stored refresh token.
     * Stores the new tokens back into the linked account.
     *
     * @return the new access token, or {@code null} if refresh failed
     */
    public String refreshAccessToken(String userId, String provider, String refreshToken) {
        String clientId     = settings.get("atlassian.oauth.client-id", "");
        String clientSecret = settings.get("atlassian.oauth.client-secret", "");
        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        try {
            String body = "grant_type=refresh_token"
                    + "&client_id="     + enc(clientId)
                    + "&client_secret=" + enc(clientSecret)
                    + "&refresh_token=" + enc(refreshToken);

            HttpRequest req = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(AUTH_BASE + "/oauth/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warnf("Atlassian token refresh failed for user=%s: HTTP %d", userId, resp.statusCode());
                return null;
            }
            JsonNode json       = mapper.readTree(resp.body());
            String newAccess    = json.path("access_token").asText("");
            String newRefresh   = json.path("refresh_token").asText(refreshToken);

            if (!newAccess.isBlank()) {
                linkedAccountService.updateOAuthTokens(userId, provider, newAccess, newRefresh);
                LOG.infof("Refreshed Atlassian OAuth token for user=%s provider=%s", userId, provider);
            }
            return newAccess.isBlank() ? null : newAccess;
        } catch (Exception e) {
            LOG.errorf(e, "Token refresh failed for user=%s provider=%s", userId, provider);
            return null;
        }
    }

    // ── Xray OAuth (Client Credentials) ─────────────────────────────────────────

    /** Map of Xray OAuth token endpoint per region. */
    private static final Map<String, String> XRAY_TOKEN_URLS = Map.of(
            "https://xray.cloud.getxray.app",    "https://xray.cloud.getxray.app/api/v2/authenticate",
            "https://eu.xray.cloud.getxray.app", "https://eu.xray.cloud.getxray.app/api/v2/authenticate"
    );

    /**
     * Fetches an Xray bearer token using the stored client credentials.
     * Xray Cloud uses a proprietary POST-with-JSON client credentials flow,
     * not the standard OAuth 2.0 client_credentials grant.
     *
     * @return bearer token string, or {@code null} on failure
     */
    @SuppressWarnings("java:S5144") // baseUrl is validated against a known allowlist
    public String getXrayBearerToken(String baseUrl, String clientId, String clientSecret) {
        // Only allow known Xray Cloud endpoints or admin-configured base URLs
        String tokenUrl = XRAY_TOKEN_URLS.getOrDefault(baseUrl, baseUrl + "/api/v2/authenticate");
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "client_id",     clientId,
                    "client_secret", clientSecret
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String token = resp.body().replace("\"", "").trim();
                return token.isBlank() ? null : token;
            }
            LOG.warnf("Xray authenticate failed (%d): %s", resp.statusCode(), resp.body());
            return null;
        } catch (Exception e) {
            LOG.errorf(e, "Xray authenticate failed for %s", baseUrl);
            return null;
        }
    }
}
