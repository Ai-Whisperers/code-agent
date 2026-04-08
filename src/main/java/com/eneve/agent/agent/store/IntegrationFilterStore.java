package com.eneve.agent.agent.store;

import com.eneve.agent.model.IntegrationFilter;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed store for the integration_filters table.
 *
 * <p>Opt-in model: {@link #isEnabled} and {@link #isWebhookEnabled} return
 * {@code false} when no row exists for the given type+key. Projects and spaces
 * must be explicitly enabled before they are used by the agent.
 */
@ApplicationScoped
public class IntegrationFilterStore {

    private static final Logger LOG = Logger.getLogger(IntegrationFilterStore.class);

    @Inject
    AgroalDataSource dataSource;

    public void upsert(String type, String key, String name, boolean enabled, boolean webhookEnabled) {
        String sql = """
                INSERT INTO integration_filters
                    (integration_type, key, name, enabled, webhook_enabled, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (integration_type, key)
                DO UPDATE SET name            = EXCLUDED.name,
                              enabled         = EXCLUDED.enabled,
                              webhook_enabled = EXCLUDED.webhook_enabled,
                              updated_at      = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, key);
            ps.setString(3, name != null ? name : "");
            ps.setBoolean(4, enabled);
            ps.setBoolean(5, webhookEnabled);
            ps.executeUpdate();
            LOG.debugf("Upserted integration filter %s/%s (enabled=%b, webhook=%b)", type, key, enabled, webhookEnabled);
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert integration filter %s/%s: %s", type, key, e.getMessage());
            throw new RuntimeException("Failed to save integration filter: " + type + "/" + key, e);
        }
    }

    public List<IntegrationFilter> listByType(String type) {
        String sql = """
                SELECT id, integration_type, key, name, enabled, webhook_enabled, created_at, updated_at
                FROM integration_filters
                WHERE integration_type = ?
                ORDER BY key
                """;
        List<IntegrationFilter> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list integration filters for type %s: %s", type, e.getMessage());
        }
        return results;
    }

    public Optional<IntegrationFilter> findByTypeAndKey(String type, String key) {
        String sql = """
                SELECT id, integration_type, key, name, enabled, webhook_enabled, created_at, updated_at
                FROM integration_filters
                WHERE integration_type = ? AND key = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find integration filter %s/%s: %s", type, key, e.getMessage());
        }
        return Optional.empty();
    }

    public boolean delete(String type, String key) {
        String sql = "DELETE FROM integration_filters WHERE integration_type = ? AND key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, key);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOG.debugf("Deleted integration filter %s/%s", type, key);
            }
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete integration filter %s/%s: %s", type, key, e.getMessage());
            return false;
        }
    }

    /**
     * Returns {@code true} only if a row exists and {@code enabled=true} (opt-in model).
     * Projects/spaces with no row are treated as disabled.
     */
    public boolean isEnabled(String type, String key) {
        return findByTypeAndKey(type, key).map(IntegrationFilter::enabled).orElse(false);
    }

    /**
     * Returns {@code true} only if a row exists and {@code webhook_enabled=true} (opt-in model).
     * Projects/spaces with no row have webhooks disabled by default.
     */
    public boolean isWebhookEnabled(String type, String key) {
        return findByTypeAndKey(type, key).map(IntegrationFilter::webhookEnabled).orElse(false);
    }

    private IntegrationFilter mapRow(ResultSet rs) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        return new IntegrationFilter(
                rs.getLong("id"),
                rs.getString("integration_type"),
                rs.getString("key"),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                rs.getBoolean("webhook_enabled"),
                createdTs != null ? createdTs.toInstant() : null,
                updatedTs != null ? updatedTs.toInstant() : null
        );
    }
}
