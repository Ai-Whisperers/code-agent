package com.eneve.agent.agent.store;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ScopeItemOverrideStore {

    private static final Logger LOG = Logger.getLogger(ScopeItemOverrideStore.class);

    @Inject
    AgroalDataSource dataSource;

    /**
     * Creates or updates the override for a given (scopeId, issueKey).
     */
    public void setOverride(String scopeId, String issueKey, String status, String updatedBy) {
        String sql = """
                INSERT INTO roadmap_item_overrides (id, roadmap_id, issue_key, override_status, updated_at, updated_by)
                VALUES (gen_random_uuid(), ?::uuid, ?, ?, now(), ?)
                ON CONFLICT ON CONSTRAINT uidx_roadmap_item_overrides
                DO UPDATE SET
                    override_status = EXCLUDED.override_status,
                    updated_at      = now(),
                    updated_by      = EXCLUDED.updated_by
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            ps.setString(2, issueKey);
            ps.setString(3, status);
            if (updatedBy != null) ps.setString(4, updatedBy);
            else ps.setNull(4, Types.VARCHAR);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("ScopeItemOverrideStore: setOverride failed for %s/%s: %s", scopeId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to set override", e);
        }
    }

    /**
     * Removes the override for a given (scopeId, issueKey).
     */
    public void clearOverride(String scopeId, String issueKey) {
        String sql = "DELETE FROM roadmap_item_overrides WHERE roadmap_id = ?::uuid AND issue_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            ps.setString(2, issueKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("ScopeItemOverrideStore: clearOverride failed for %s/%s: %s", scopeId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to clear override", e);
        }
    }

    /**
     * Returns all overrides for a scope as a map from issueKey to override status.
     */
    public Map<String, String> findByScope(String scopeId) {
        String sql = "SELECT issue_key, override_status FROM roadmap_item_overrides WHERE roadmap_id = ?::uuid";
        Map<String, String> result = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("issue_key"), rs.getString("override_status"));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeItemOverrideStore: findByScope failed for %s: %s", scopeId, e.getMessage());
        }
        return result;
    }

    /**
     * Returns the override status for a specific item, if any.
     */
    public Optional<String> getOverride(String scopeId, String issueKey) {
        String sql = "SELECT override_status FROM roadmap_item_overrides WHERE roadmap_id = ?::uuid AND issue_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            ps.setString(2, issueKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString("override_status"));
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeItemOverrideStore: getOverride failed for %s/%s: %s", scopeId, issueKey, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns true if the item has an override status of ACCEPTED or REMOVED.
     */
    public boolean isOverridden(String scopeId, String issueKey) {
        return getOverride(scopeId, issueKey).isPresent();
    }
}
