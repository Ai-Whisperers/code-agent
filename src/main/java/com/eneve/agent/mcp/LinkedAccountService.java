package com.eneve.agent.mcp;

import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.mcp.LinkedAccountStore.AccountRow;
import com.eneve.agent.settings.SettingsEncryption;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.xray.XrayService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing user-linked Jira and Confluence accounts.
 * Handles encryption/decryption of API tokens.
 * Provides fallback to system credentials when no user profile exists.
 */
@ApplicationScoped
public class LinkedAccountService {

    private static final Logger LOG = Logger.getLogger(LinkedAccountService.class);
    private static final String MASKED = "****";

    @Inject
    LinkedAccountStore store;

    @Inject
    SettingsEncryption encryption;

    @Inject
    JiraService jiraService;

    @Inject
    ConfluenceService confluenceService;

    @Inject
    XrayService xrayService;

    @Inject
    SettingsService settings;

    public record AccountView(
            String provider,
            String displayName,
            String baseUrl,
            String username,
            String apiTokenMasked,
            /** "oauth" or "apitoken" */
            String authType,
            String createdAt,
            String updatedAt
    ) {}

    // ─── Read API (API-safe, tokens masked) ─────────────────────────────────────

    public List<AccountView> listForUser(String userId) {
        return store.findByUser(userId).stream()
                .map(this::toView)
                .toList();
    }

    public Optional<AccountView> findForUser(String userId, String provider) {
        return store.findByUserAndProvider(userId, provider)
                .map(this::toView);
    }

    // ─── Write API ────────────────────────────────────────────────────────────────

    public void upsert(String userId, String provider, String displayName,
                       String baseUrl, String username, String apiToken) {
        String encrypted = encryption.encrypt(apiToken);
        store.upsert(userId, provider, displayName, baseUrl, username,
                encrypted, "apitoken", null);
    }

    /** Persists an OAuth-linked account (access token + optional refresh token). */
    public void upsertOAuth(String userId, String provider, String displayName,
                            String baseUrl, String username,
                            String accessToken, String refreshToken) {
        String encAccess  = encryption.encrypt(accessToken);
        String encRefresh = refreshToken != null ? encryption.encrypt(refreshToken) : null;
        store.upsert(userId, provider, displayName, baseUrl, username,
                encAccess, "oauth", encRefresh);
    }

    /** Updates only the OAuth tokens for an existing linked account (e.g. after token refresh). */
    public void updateOAuthTokens(String userId, String provider,
                                  String newAccessToken, String newRefreshToken) {
        String encAccess  = encryption.encrypt(newAccessToken);
        String encRefresh = newRefreshToken != null ? encryption.encrypt(newRefreshToken) : null;
        store.updateOAuthTokens(userId, provider, encAccess, encRefresh);
    }

    /**
     * Returns the decrypted refresh token for an OAuth-linked account,
     * or {@code null} if not stored.
     */
    public String getRefreshToken(String userId, String provider) {
        return store.findByUserAndProvider(userId, provider)
                .map(row -> {
                    String enc = row.refreshTokenEnc();
                    return enc != null ? encryption.decrypt(enc) : null;
                })
                .orElse(null);
    }

    public boolean delete(String userId, String provider) {
        return store.delete(userId, provider);
    }

    // ─── Credential resolution (used by MCP tools) ─────────────────────────────────

    /**
     * Resolve Jira credentials for the given user.
     * Returns user-linked account if available, falls back to system credentials
     * only if mcp.system-credential-fallback.enabled is true.
     *
     * @return Optional of credentials, or empty if no linked account and fallback disabled
     */
    public Optional<JiraService.JiraCredentials> resolveJira(String userId) {
        Optional<AccountRow> row = store.findByUserAndProvider(userId, "jira");
        if (row.isPresent()) {
            String token = encryption.decrypt(row.get().apiTokenEnc());
            return Optional.of(new JiraService.JiraCredentials(
                    row.get().baseUrl(),
                    row.get().username(),
                    token
            ));
        }

        // Fallback to system credentials
        if (Boolean.parseBoolean(settings.get("mcp.system-credential-fallback.enabled", "false")) && jiraService.isConfigured()) {
            LOG.debugf("No linked Jira account for user=%s, using system credentials", userId);
            return Optional.of(new JiraService.JiraCredentials(
                    jiraService.getBaseUrl(),
                    jiraService.getUser(),
                    jiraService.getApiToken()
            ));
        }

        return Optional.empty();
    }

    /**
     * Resolve Confluence credentials for the given user.
     * Returns user-linked account if available, falls back to system credentials
     * only if mcp.system-credential-fallback.enabled is true.
     *
     * @return Optional of credentials, or empty if no linked account and fallback disabled
     */
    public Optional<ConfluenceService.ConfluenceCredentials> resolveConfluence(String userId) {
        Optional<AccountRow> row = store.findByUserAndProvider(userId, "confluence");
        if (row.isPresent()) {
            String token = encryption.decrypt(row.get().apiTokenEnc());
            return Optional.of(new ConfluenceService.ConfluenceCredentials(
                    row.get().baseUrl(),
                    row.get().username(),
                    token
            ));
        }

        // Jira and Confluence share the same Atlassian Cloud credentials — fall back to linked Jira account
        Optional<AccountRow> jiraRow = store.findByUserAndProvider(userId, "jira");
        if (jiraRow.isPresent()) {
            LOG.debugf("No linked Confluence account for user=%s, using linked Jira credentials", userId);
            String token = encryption.decrypt(jiraRow.get().apiTokenEnc());
            return Optional.of(new ConfluenceService.ConfluenceCredentials(
                    jiraRow.get().baseUrl(),
                    jiraRow.get().username(),
                    token
            ));
        }

        // Fallback to system credentials
        if (Boolean.parseBoolean(settings.get("mcp.system-credential-fallback.enabled", "false")) && confluenceService.isEnabled()) {
            LOG.debugf("No linked Confluence account for user=%s, using system credentials", userId);
            return Optional.of(new ConfluenceService.ConfluenceCredentials(
                    confluenceService.getBaseUrl(),
                    confluenceService.getUser(),
                    confluenceService.getApiToken()
            ));
        }

        return Optional.empty();
    }

    // ─── Test connection ─────────────────────────────────────────────────────────

    /**
     * Test Jira connection with the given credentials.
     * Does NOT store them; used by the test endpoint before saving.
     */
    public boolean testJiraConnection(String baseUrl, String username, String apiToken) {
        try {
            return JiraService.testConnection(baseUrl, username, apiToken);
        } catch (Exception e) {
            LOG.warnf("Jira connection test failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Test Confluence connection with the given credentials.
     * Does NOT store them; used by the test endpoint before saving.
     */
    public boolean testConfluenceConnection(String baseUrl, String username, String apiToken) {
        try {
            return ConfluenceService.testConnection(baseUrl, username, apiToken);
        } catch (Exception e) {
            LOG.warnf("Confluence connection test failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Test the stored Jira credentials for the given user.
     * Returns empty if no linked account exists.
     */
    public Optional<Boolean> testStoredJiraConnection(String userId) {
        Optional<AccountRow> row = store.findByUserAndProvider(userId, "jira");
        if (row.isEmpty()) return Optional.empty();
        String token = encryption.decrypt(row.get().apiTokenEnc());
        try {
            return Optional.of(JiraService.testConnection(row.get().baseUrl(), row.get().username(), token));
        } catch (Exception e) {
            LOG.warnf("Jira stored connection test failed: %s", e.getMessage());
            return Optional.of(false);
        }
    }

    /**
     * Test the stored Confluence credentials for the given user.
     * Returns empty if no linked account exists.
     */
    public Optional<Boolean> testStoredConfluenceConnection(String userId) {
        Optional<AccountRow> row = store.findByUserAndProvider(userId, "confluence");
        if (row.isEmpty()) return Optional.empty();
        String token = encryption.decrypt(row.get().apiTokenEnc());
        try {
            return Optional.of(ConfluenceService.testConnection(row.get().baseUrl(), row.get().username(), token));
        } catch (Exception e) {
            LOG.warnf("Confluence stored connection test failed: %s", e.getMessage());
            return Optional.of(false);
        }
    }

    // ─── Xray Cloud credential resolution ────────────────────────────────────────

    /**
     * Resolve Xray Cloud credentials for the given user.
     *
     * <p>When {@code userId} is {@code null} or blank (scheduler / background job context
     * with no user session), the per-user lookup is skipped and system credentials are
     * returned directly if {@link XrayService#isConfigured()} is {@code true}.
     *
     * <p>For interactive users, returns the per-user linked account if present; otherwise
     * falls back to system credentials only when {@code mcp.system-credential-fallback.enabled}
     * is {@code true}.
     *
     * <p>Credential mapping in {@code user_linked_accounts}:
     * {@code username} = client_id, {@code api_token_enc} = encrypted client_secret,
     * {@code base_url} = Xray Cloud region URL.
     *
     * @param userId user identifier, or {@code null} for userless job/scheduler context
     * @return Optional of credentials, or empty if Xray is not configured
     */
    public Optional<XrayService.XrayCredentials> resolveXray(String userId) {
        // Userless context (scheduler / background job) — bypass per-user lookup
        if (userId == null || userId.isBlank()) {
            if (xrayService.isConfigured()) {
                LOG.debug("Resolving Xray credentials for system/job context (no user)");
                return Optional.of(xrayService.systemCredentials());
            }
            return Optional.empty();
        }

        Optional<AccountRow> row = store.findByUserAndProvider(userId, "xray");
        if (row.isPresent()) {
            String secret = encryption.decrypt(row.get().apiTokenEnc());
            return Optional.of(new XrayService.XrayCredentials(
                    row.get().username(),
                    secret,
                    row.get().baseUrl()
            ));
        }

        if (Boolean.parseBoolean(settings.get("mcp.system-credential-fallback.enabled", "false"))
                && xrayService.isConfigured()) {
            LOG.debugf("No linked Xray account for user=%s, using system credentials", userId);
            return Optional.of(xrayService.systemCredentials());
        }

        return Optional.empty();
    }

    /**
     * Test Xray Cloud connection with the given credentials.
     * Authentication itself is the connection test — no separate ping endpoint exists.
     * Does NOT store the credentials.
     */
    public boolean testXrayConnection(String baseUrl, String clientId, String clientSecret) {
        try {
            return XrayService.testConnection(baseUrl, clientId, clientSecret);
        } catch (Exception e) {
            LOG.warnf("Xray connection test failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Test the stored Xray Cloud credentials for the given user.
     * Returns empty if no linked account exists.
     */
    public Optional<Boolean> testStoredXrayConnection(String userId) {
        Optional<AccountRow> row = store.findByUserAndProvider(userId, "xray");
        if (row.isEmpty()) return Optional.empty();
        String secret = encryption.decrypt(row.get().apiTokenEnc());
        try {
            return Optional.of(XrayService.testConnection(row.get().baseUrl(), row.get().username(), secret));
        } catch (Exception e) {
            LOG.warnf("Xray stored connection test failed: %s", e.getMessage());
            return Optional.of(false);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private AccountView toView(AccountRow row) {
        return new AccountView(
                row.provider(),
                row.displayName(),
                row.baseUrl(),
                row.username(),
                MASKED,
                row.authType() != null ? row.authType() : "apitoken",
                row.createdAt() != null ? row.createdAt().toString() : null,
                row.updatedAt() != null ? row.updatedAt().toString() : null
        );
    }
}
