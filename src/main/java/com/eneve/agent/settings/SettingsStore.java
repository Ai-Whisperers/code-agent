package com.eneve.agent.settings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * PostgreSQL-backed store for the agent_settings table.
 * Values for secret rows are expected to already be encrypted before calling
 * {@link #upsert} and are returned as-is (still encrypted) from read methods.
 * Encryption/decryption is the responsibility of {@link SettingsService}.
 */
@ApplicationScoped
public class SettingsStore {

    private static final Logger LOG = Logger.getLogger(SettingsStore.class);

    @Inject
    AgroalDataSource dataSource;

    public Optional<SettingRow> findByKey(String key) {
        String sql = "SELECT key, value, is_secret, description, updated_at "
                   + "FROM agent_settings WHERE key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find setting '%s': %s", key, e.getMessage());
        }
        return Optional.empty();
    }

    public List<SettingRow> findAll() {
        String sql = "SELECT key, value, is_secret, description, updated_at "
                   + "FROM agent_settings ORDER BY key";
        List<SettingRow> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list settings: %s", e.getMessage());
        }
        return results;
    }

    public void upsert(String key, String value, boolean isSecret, String description) {
        String sql = """
                INSERT INTO agent_settings (key, value, is_secret, description, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (key)
                DO UPDATE SET value       = EXCLUDED.value,
                              is_secret   = EXCLUDED.is_secret,
                              description = EXCLUDED.description,
                              updated_at  = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setBoolean(3, isSecret);
            if (description != null) {
                ps.setString(4, description);
            } else {
                ps.setNull(4, Types.VARCHAR);
            }
            ps.executeUpdate();
            LOG.debugf("Upserted setting '%s' (secret=%b)", key, isSecret);
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert setting '%s': %s", key, e.getMessage());
            throw new RuntimeException("Failed to save setting: " + key, e);
        }
    }

    public boolean delete(String key) {
        String sql = "DELETE FROM agent_settings WHERE key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOG.debugf("Deleted setting '%s'", key);
            }
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete setting '%s': %s", key, e.getMessage());
            return false;
        }
    }

    private SettingRow mapRow(ResultSet rs) throws SQLException {
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        return new SettingRow(
                rs.getString("key"),
                rs.getString("value"),
                rs.getBoolean("is_secret"),
                rs.getString("description"),
                updatedTs != null ? updatedTs.toInstant() : null
        );
    }
}
