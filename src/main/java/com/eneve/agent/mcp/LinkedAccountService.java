package com.eneve.agent.mcp;

import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.mcp.LinkedAccountStore.AccountRow;
import com.eneve.agent.settings.SettingsEncryption;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    @ConfigProperty(name = "mcp.system-credential-fallback.enabled", defaultValue = "false")
    boolean systemCredentialFallbackEnabled;

    // Use credential records from the respective services
    public record AccountView(
            String provider,
            String displayName,
            String baseUrl,
            String username,
            String apiTokenMasked,
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
        store.upsert(userId, provider, displayName, baseUrl, username, encrypted);
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
        if (systemCredentialFallbackEnabled && jiraService.isConfigured()) {
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
        if (systemCredentialFallbackEnabled && confluenceService.isEnabled()) {
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

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private AccountView toView(AccountRow row) {
        return new AccountView(
                row.provider(),
                row.displayName(),
                row.baseUrl(),
                row.username(),
                MASKED,
                row.createdAt() != null ? row.createdAt().toString() : null,
                row.updatedAt() != null ? row.updatedAt().toString() : null
        );
    }
}
