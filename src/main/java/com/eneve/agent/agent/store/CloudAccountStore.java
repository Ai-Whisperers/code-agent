package com.eneve.agent.agent.store;

import com.eneve.agent.model.CloudAccount;
import com.eneve.agent.model.CloudAccountType;
import com.eneve.agent.settings.SettingsEncryption;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL-backed store for cloud accounts.
 * Provider credentials are stored as an AES-256-GCM encrypted JSON blob using
 * {@link SettingsEncryption}. The plaintext is a JSON object whose keys are
 * provider-specific (e.g. awsKeyId/awsSecret for AWS, clientId/clientSecret/
 * tenantId/subscriptionId for Azure).
 */
@ApplicationScoped
public class CloudAccountStore {

    private static final Logger LOG = Logger.getLogger(CloudAccountStore.class);
    @Inject ObjectMapper mapper;
    private static final String MASKED = "****";

    @Inject
    AgroalDataSource dataSource;

    @Inject
    SettingsEncryption encryption;

    // ──────────────────────────────────────────────────────────────────────────

    public List<CloudAccount> listCloudAccounts() {
        String sql = """
                SELECT id, name, description, type, credentials, created_at, updated_at
                FROM cloud_accounts
                ORDER BY name
                """;
        List<CloudAccount> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs, true));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list cloud accounts: %s", e.getMessage());
        }
        return results;
    }

    public Optional<CloudAccount> getCloudAccount(String id) {
        return fetchCloudAccount(id, true);
    }

    /**
     * Returns the cloud account with plaintext (unmasked) credentials.
     * Intended for internal use only — never expose the result via an API response.
     */
    public Optional<CloudAccount> getCloudAccountUnmasked(String id) {
        return fetchCloudAccount(id, false);
    }

    private Optional<CloudAccount> fetchCloudAccount(String id, boolean maskSecrets) {
        String sql = """
                SELECT id, name, description, type, credentials, created_at, updated_at
                FROM cloud_accounts
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs, maskSecrets));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to get cloud account %s: %s", id, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Creates or updates a cloud account. The {@code credentials} map is encrypted
     * before persistence. Pass null credentials to leave the stored value unchanged
     * (useful when the UI sends back masked values).
     */
    public void upsertCloudAccount(CloudAccount account) {
        String encryptedCreds = encryptCredentials(account.credentials());

        String sql;
        if (encryptedCreds != null) {
            sql = """
                    INSERT INTO cloud_accounts (id, name, description, type, credentials, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, now(), now())
                    ON CONFLICT (id) DO UPDATE
                        SET name        = EXCLUDED.name,
                            description = EXCLUDED.description,
                            type        = EXCLUDED.type,
                            credentials = EXCLUDED.credentials,
                            updated_at  = now()
                    """;
        } else {
            // No credential update — keep existing encrypted blob
            sql = """
                    INSERT INTO cloud_accounts (id, name, description, type, credentials, created_at, updated_at)
                    VALUES (?, ?, ?, ?, NULL, now(), now())
                    ON CONFLICT (id) DO UPDATE
                        SET name        = EXCLUDED.name,
                            description = EXCLUDED.description,
                            type        = EXCLUDED.type,
                            updated_at  = now()
                    """;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, account.id());
            ps.setString(2, account.name());
            ps.setString(3, account.description());
            ps.setString(4, account.type() != null ? account.type().name() : CloudAccountType.AWS.name());
            if (encryptedCreds != null) {
                ps.setString(5, encryptedCreds);
            }
            ps.executeUpdate();
            LOG.debugf("Upserted cloud account %s", account.id());
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert cloud account %s: %s", account.id(), e.getMessage());
        }
    }

    public boolean deleteCloudAccount(String id) {
        String sql = "DELETE FROM cloud_accounts WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            int rows = ps.executeUpdate();
            LOG.debugf("Deleted cloud account %s (%d rows)", id, rows);
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete cloud account %s: %s", id, e.getMessage());
            return false;
        }
    }

    // ── Row mapper ────────────────────────────────────────────────────────────

    private CloudAccount mapRow(ResultSet rs, boolean maskSecrets) throws SQLException {
        String rawCreds = rs.getString("credentials");
        Map<String, String> credentials = decryptAndMask(rawCreds, maskSecrets);

        CloudAccountType type;
        try {
            type = CloudAccountType.valueOf(rs.getString("type"));
        } catch (IllegalArgumentException | NullPointerException e) {
            type = CloudAccountType.OTHER;
        }

        return new CloudAccount(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                type,
                credentials,
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns credentials with all values replaced by "****".
     * The keys are preserved so the UI knows which fields are configured.
     */
    private Map<String, String> decryptAndMask(String encryptedJson, boolean mask) {
        if (encryptedJson == null || encryptedJson.isBlank()) {
            return Map.of();
        }
        try {
            String json = encryption.isConfigured()
                    ? encryption.decrypt(encryptedJson)
                    : encryptedJson;
            Map<String, String> plain = mapper.readValue(json, new TypeReference<>() {});
            if (!mask) return plain;
            Map<String, String> masked = new LinkedHashMap<>();
            plain.forEach((k, v) -> masked.put(k, v != null && !v.isBlank() ? MASKED : ""));
            return masked;
        } catch (Exception e) {
            LOG.warnf("Failed to decrypt cloud account credentials: %s", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Serializes and encrypts the credential map.
     * Returns null when the map is null, empty, or all values are the mask sentinel
     * (meaning the caller sent back unchanged masked values — nothing to store).
     */
    private String encryptCredentials(Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) return null;
        boolean allMasked = credentials.values().stream().allMatch(MASKED::equals);
        if (allMasked) return null;

        try {
            String json = mapper.writeValueAsString(credentials);
            return encryption.isConfigured() ? encryption.encrypt(json) : json;
        } catch (Exception e) {
            LOG.warnf("Failed to encrypt cloud account credentials: %s", e.getMessage());
            return null;
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
