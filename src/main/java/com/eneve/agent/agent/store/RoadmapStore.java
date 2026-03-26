package com.eneve.agent.agent.store;

import com.eneve.agent.model.RoadmapRecord;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class RoadmapStore {

    private static final Logger LOG = Logger.getLogger(RoadmapStore.class);

    @Inject
    AgroalDataSource dataSource;

    public RoadmapRecord create(String name, String label,
                                 String epicIssuetype, String featureIssuetype,
                                 String userstoryIssuetype) {
        String id = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO roadmaps (id, name, label, epic_issuetype, feature_issuetype, userstory_issuetype)
                VALUES (?::uuid, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, label);
            ps.setString(4, epicIssuetype);
            ps.setString(5, featureIssuetype);
            ps.setString(6, userstoryIssuetype);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("RoadmapStore: failed to create roadmap: %s", e.getMessage());
            throw new RuntimeException("Failed to create roadmap", e);
        }
        return findById(id).orElseThrow();
    }

    public Optional<RoadmapRecord> findById(String id) {
        String sql = """
                SELECT id, name, label, epic_issuetype, feature_issuetype, userstory_issuetype, created_at
                FROM roadmaps WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("RoadmapStore: failed to find roadmap %s: %s", id, e.getMessage());
        }
        return Optional.empty();
    }

    public List<RoadmapRecord> findAll() {
        String sql = """
                SELECT id, name, label, epic_issuetype, feature_issuetype, userstory_issuetype, created_at
                FROM roadmaps ORDER BY created_at DESC
                """;
        List<RoadmapRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) results.add(mapRow(rs));
        } catch (SQLException e) {
            LOG.errorf("RoadmapStore: failed to list roadmaps: %s", e.getMessage());
        }
        return results;
    }

    public void update(String id, String name, String label,
                       String epicIssuetype, String featureIssuetype,
                       String userstoryIssuetype) {
        String sql = """
                UPDATE roadmaps SET name = ?, label = ?,
                    epic_issuetype = ?, feature_issuetype = ?, userstory_issuetype = ?
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, label);
            ps.setString(3, epicIssuetype);
            ps.setString(4, featureIssuetype);
            ps.setString(5, userstoryIssuetype);
            ps.setString(6, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("RoadmapStore: failed to update roadmap %s: %s", id, e.getMessage());
            throw new RuntimeException("Failed to update roadmap", e);
        }
    }

    public void delete(String id) {
        String sql = "DELETE FROM roadmaps WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("RoadmapStore: failed to delete roadmap %s: %s", id, e.getMessage());
            throw new RuntimeException("Failed to delete roadmap", e);
        }
    }

    private RoadmapRecord mapRow(ResultSet rs) throws SQLException {
        return new RoadmapRecord(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("label"),
                rs.getString("epic_issuetype"),
                rs.getString("feature_issuetype"),
                rs.getString("userstory_issuetype"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
