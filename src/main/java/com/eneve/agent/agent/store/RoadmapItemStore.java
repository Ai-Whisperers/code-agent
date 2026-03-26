package com.eneve.agent.agent.store;

import com.eneve.agent.model.RoadmapItem;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC store for {@code roadmap_items}.
 * The table is the source of truth for the Jira issue structure within a roadmap.
 * It is populated by the sync step and read by tree-view and review dispatch logic.
 */
@ApplicationScoped
public class RoadmapItemStore {

    private static final Logger LOG = Logger.getLogger(RoadmapItemStore.class);

    @Inject
    AgroalDataSource dataSource;

    /**
     * Atomically replaces all items for {@code roadmapId} with the provided list.
     * Uses a single connection with a transaction: deletes existing rows then
     * batch-inserts the new ones.
     */
    public void replaceAll(String roadmapId, List<RoadmapItem> items) {
        String deleteSql = "DELETE FROM roadmap_items WHERE roadmap_id = ?::uuid";
        String insertSql = """
                INSERT INTO roadmap_items
                    (roadmap_id, issue_key, issue_type, parent_key, grandparent_key, summary, jira_status, jira_modified_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
                    del.setString(1, roadmapId);
                    del.executeUpdate();
                }
                if (!items.isEmpty()) {
                    try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                        for (RoadmapItem item : items) {
                            ins.setString(1, roadmapId);
                            ins.setString(2, item.issueKey());
                            ins.setString(3, item.issueType());
                            ins.setString(4, item.parentKey());
                            ins.setString(5, item.grandparentKey());
                            ins.setString(6, item.summary());
                            ins.setString(7, item.jiraStatus());
                            if (item.jiraModifiedAt() != null) {
                                ins.setTimestamp(8, Timestamp.from(item.jiraModifiedAt()));
                            } else {
                                ins.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
                            }
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemStore.replaceAll: failed for roadmap %s: %s", roadmapId, e.getMessage());
            throw new RuntimeException("Failed to replace roadmap items", e);
        }
    }

    /** Returns all items for a roadmap ordered by issue type then key. */
    public List<RoadmapItem> findByRoadmap(String roadmapId) {
        String sql = """
                SELECT id, roadmap_id, issue_key, issue_type, parent_key, grandparent_key,
                       summary, jira_status, synced_at, jira_modified_at
                FROM roadmap_items
                WHERE roadmap_id = ?::uuid
                ORDER BY issue_type, issue_key
                """;
        List<RoadmapItem> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roadmapId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemStore.findByRoadmap: failed for %s: %s", roadmapId, e.getMessage());
        }
        return results;
    }

    /** Returns the number of roadmap items whose {@code parent_key} equals {@code parentKey}. */
    public int countChildrenByParent(String roadmapId, String parentKey) {
        String sql = "SELECT COUNT(*) FROM roadmap_items WHERE roadmap_id = ?::uuid AND parent_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roadmapId);
            ps.setString(2, parentKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemStore.countChildrenByParent: %s / %s: %s",
                    roadmapId, parentKey, e.getMessage());
        }
        return 0;
    }

    /** Returns a single item by roadmap and issue key. */
    public Optional<RoadmapItem> findByRoadmapAndIssueKey(String roadmapId, String issueKey) {
        String sql = """
                SELECT id, roadmap_id, issue_key, issue_type, parent_key, grandparent_key,
                       summary, jira_status, synced_at, jira_modified_at
                FROM roadmap_items
                WHERE roadmap_id = ?::uuid AND issue_key = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roadmapId);
            ps.setString(2, issueKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemStore.findByRoadmapAndIssueKey: %s / %s: %s",
                    roadmapId, issueKey, e.getMessage());
        }
        return Optional.empty();
    }

    private RoadmapItem mapRow(ResultSet rs) throws SQLException {
        Timestamp syncedAt      = rs.getTimestamp("synced_at");
        Timestamp jiraModifiedAt = rs.getTimestamp("jira_modified_at");
        return new RoadmapItem(
                rs.getString("id"),
                rs.getString("roadmap_id"),
                rs.getString("issue_key"),
                rs.getString("issue_type"),
                rs.getString("parent_key"),
                rs.getString("grandparent_key"),
                rs.getString("summary"),
                rs.getString("jira_status"),
                syncedAt      != null ? syncedAt.toInstant()      : null,
                jiraModifiedAt != null ? jiraModifiedAt.toInstant() : null
        );
    }
}
