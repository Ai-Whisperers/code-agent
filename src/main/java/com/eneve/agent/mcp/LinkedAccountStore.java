package com.eneve.agent.mcp;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed store for user_linked_accounts table.
 * Token values are stored encrypted; encryption/decryption is handled by LinkedAccountService.
 */
@ApplicationScoped
public class LinkedAccountStore {

    private static final Logger LOG = Logger.getLogger(LinkedAccountStore.class);

    @Inject
    AgroalDataSource dataSource;

    public record AccountRow(
            Long id,
            String userId,
            String provider,
            String displayName,
            String baseUrl,
            String username,
            String apiTokenEnc,
            String authType,
            String refreshTokenEnc,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public Optional<AccountRow> findByUserAndProvider(String userId, String provider) {
        String sql = """
                SELECT id, user_id, provider, display_name, base_url, username,
                       api_token_enc, auth_type, refresh_token_enc, created_at, updated_at
                FROM user_linked_accounts
                WHERE user_id = ? AND provider = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, provider);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find account for user=%s provider=%s: %s", userId, provider, e.getMessage());
        }
        return Optional.empty();
    }

    public List<AccountRow> findByUser(String userId) {
        String sql = """
                SELECT id, user_id, provider, display_name, base_url, username,
                       api_token_enc, auth_type, refresh_token_enc, created_at, updated_at
                FROM user_linked_accounts
                WHERE user_id = ?
                ORDER BY provider
                """;
        List<AccountRow> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list accounts for user=%s: %s", userId, e.getMessage());
        }
        return results;
    }

    public void upsert(String userId, String provider, String displayName,
                       String baseUrl, String username, String apiTokenEnc) {
        upsert(userId, provider, displayName, baseUrl, username, apiTokenEnc, "apitoken", null);
    }

    public void upsert(String userId, String provider, String displayName,
                       String baseUrl, String username, String apiTokenEnc,
                       String authType, String refreshTokenEnc) {
        String sql = """
                INSERT INTO user_linked_accounts
                (user_id, provider, display_name, base_url, username,
                 api_token_enc, auth_type, refresh_token_enc, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (user_id, provider)
                DO UPDATE SET display_name      = EXCLUDED.display_name,
                              base_url          = EXCLUDED.base_url,
                              username          = EXCLUDED.username,
                              api_token_enc     = EXCLUDED.api_token_enc,
                              auth_type         = EXCLUDED.auth_type,
                              refresh_token_enc = EXCLUDED.refresh_token_enc,
                              updated_at        = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, provider);
            ps.setString(3, displayName);
            ps.setString(4, baseUrl);
            ps.setString(5, username);
            ps.setString(6, apiTokenEnc);
            ps.setString(7, authType != null ? authType : "apitoken");
            ps.setString(8, refreshTokenEnc);
            ps.executeUpdate();
            LOG.infof("Upserted linked account for user=%s provider=%s authType=%s",
                    userId, provider, authType);
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert account for user=%s provider=%s: %s",
                    userId, provider, e.getMessage());
            throw new RuntimeException("Failed to save linked account: " + provider, e);
        }
    }

    /** Updates only the OAuth tokens without touching other fields. */
    public void updateOAuthTokens(String userId, String provider,
                                  String accessTokenEnc, String refreshTokenEnc) {
        String sql = """
                UPDATE user_linked_accounts
                SET api_token_enc     = ?,
                    refresh_token_enc = ?,
                    updated_at        = now()
                WHERE user_id = ? AND provider = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, accessTokenEnc);
            ps.setString(2, refreshTokenEnc);
            ps.setString(3, userId);
            ps.setString(4, provider);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to update OAuth tokens for user=%s provider=%s: %s",
                    userId, provider, e.getMessage());
        }
    }

    public boolean delete(String userId, String provider) {
        String sql = "DELETE FROM user_linked_accounts WHERE user_id = ? AND provider = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, provider);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOG.infof("Deleted linked account for user=%s provider=%s", userId, provider);
            }
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete account for user=%s provider=%s: %s", userId, provider, e.getMessage());
            return false;
        }
    }

    private AccountRow mapRow(ResultSet rs) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        return new AccountRow(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("provider"),
                rs.getString("display_name"),
                rs.getString("base_url"),
                rs.getString("username"),
                rs.getString("api_token_enc"),
                rs.getString("auth_type"),
                rs.getString("refresh_token_enc"),
                createdTs != null ? createdTs.toInstant() : null,
                updatedTs != null ? updatedTs.toInstant() : null
        );
    }
}
