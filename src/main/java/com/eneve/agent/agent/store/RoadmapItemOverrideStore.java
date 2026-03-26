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
public class RoadmapItemOverrideStore {

    private static final Logger LOG = Logger.getLogger(RoadmapItemOverrideStore.class);

    @Inject
    AgroalDataSource dataSource;

    /**
     * Creates or updates the override for a given (roadmapId, issueKey).
     */
    public void setOverride(String roadmapId, String issueKey, String status, String updatedBy) {
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
            ps.setString(1, roadmapId);
            ps.setString(2, issueKey);
            ps.setString(3, status);
            if (updatedBy != null) ps.setString(4, updatedBy);
            else ps.setNull(4, Types.VARCHAR);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemOverrideStore: setOverride failed for %s/%s: %s", roadmapId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to set override", e);
        }
    }

    /**
     * Removes the override for a given (roadmapId, issueKey).
     */
    public void clearOverride(String roadmapId, String issueKey) {
        String sql = "DELETE FROM roadmap_item_overrides WHERE roadmap_id = ?::uuid AND issue_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roadmapId);
            ps.setString(2, issueKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemOverrideStore: clearOverride failed for %s/%s: %s", roadmapId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to clear override", e);
        }
    }

    /**
     * Returns all overrides for a roadmap as a map from issueKey to override status.
     */
    public Map<String, String> findByRoadmap(String roadmapId) {
        String sql = "SELECT issue_key, override_status FROM roadmap_item_overrides WHERE roadmap_id = ?::uuid";
        Map<String, String> result = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roadmapId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("issue_key"), rs.getString("override_status"));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemOverrideStore: findByRoadmap failed for %s: %s", roadmapId, e.getMessage());
        }
        return result;
    }

    /**
     * Returns the override status for a specific item, if any.
     */
    public Optional<String> getOverride(String roadmapId, String issueKey) {
        String sql = "SELECT override_status FROM roadmap_item_overrides WHERE roadmap_id = ?::uuid AND issue_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roadmapId);
            ps.setString(2, issueKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString("override_status"));
            }
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemOverrideStore: getOverride failed for %s/%s: %s", roadmapId, issueKey, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns true if the item has an override status of ACCEPTED or REMOVED.
     */
    public boolean isOverridden(String roadmapId, String issueKey) {
        return getOverride(roadmapId, issueKey).isPresent();
    }
}
