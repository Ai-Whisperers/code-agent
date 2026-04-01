package com.eneve.agent.agent.store;

import com.eneve.agent.model.ScopeItem;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC store for {@code scope_items}.
 * The table is the source of truth for the Jira issue structure within a scope.
 * It is populated by the sync step and read by tree-view and review dispatch logic.
 */
@ApplicationScoped
public class ScopeItemStore {

    private static final Logger LOG = Logger.getLogger(ScopeItemStore.class);

    @Inject
    AgroalDataSource dataSource;

    /**
     * Atomically replaces all items for {@code scopeId} with the provided list.
     * Uses a single connection with a transaction: deletes existing rows then
     * batch-inserts the new ones.
     */
    public void replaceAll(String scopeId, List<ScopeItem> items) {
        String deleteSql = "DELETE FROM scope_items WHERE scope_id = ?::uuid";
        String insertSql = """
                INSERT INTO scope_items
                    (scope_id, issue_key, issue_type, parent_key, grandparent_key, summary,
                     jira_status, jira_modified_at, assignee, reporter, sprint_name, sprint_start, sprint_end)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
                    del.setString(1, scopeId);
                    del.executeUpdate();
                }
                if (!items.isEmpty()) {
                    try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                        for (ScopeItem item : items) {
                            ins.setString(1, scopeId);
                            ins.setString(2, item.issueKey());
                            ins.setString(3, item.issueType());
                            ins.setString(4, item.parentKey());
                            ins.setString(5, item.grandparentKey());
                            ins.setString(6, item.summary());
                            ins.setString(7, item.jiraStatus());
                            setTimestamp(ins, 8, item.jiraModifiedAt());
                            ins.setString(9,  item.assignee());
                            ins.setString(10, item.reporter());
                            ins.setString(11, item.sprintName());
                            setTimestamp(ins, 12, item.sprintStart());
                            setTimestamp(ins, 13, item.sprintEnd());
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
            LOG.errorf("ScopeItemStore.replaceAll: failed for scope %s: %s", scopeId, e.getMessage());
            throw new RuntimeException("Failed to replace scope items", e);
        }
    }

    /** Returns all items for a scope ordered by issue type then key. */
    public List<ScopeItem> findByScope(String scopeId) {
        String sql = """
                SELECT id, scope_id, issue_key, issue_type, parent_key, grandparent_key,
                       summary, jira_status, synced_at, jira_modified_at,
                       assignee, reporter, sprint_name, sprint_start, sprint_end
                FROM scope_items
                WHERE scope_id = ?::uuid
                ORDER BY issue_type, issue_key
                """;
        List<ScopeItem> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeItemStore.findByScope: failed for %s: %s", scopeId, e.getMessage());
        }
        return results;
    }

    /**
     * Returns items that have a sprint assigned, ordered by sprint_start then issue_type then issue_key.
     * Used for building the sprint/Gantt view.
     */
    public List<ScopeItem> findSprintItems(String scopeId) {
        String sql = """
                SELECT id, scope_id, issue_key, issue_type, parent_key, grandparent_key,
                       summary, jira_status, synced_at, jira_modified_at,
                       assignee, reporter, sprint_name, sprint_start, sprint_end
                FROM scope_items
                WHERE scope_id = ?::uuid AND sprint_name IS NOT NULL
                ORDER BY sprint_start NULLS LAST, issue_type, issue_key
                """;
        List<ScopeItem> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeItemStore.findSprintItems: failed for %s: %s", scopeId, e.getMessage());
        }
        return results;
    }

    /** Returns the number of scope items whose {@code parent_key} equals {@code parentKey}. */
    public int countChildrenByParent(String scopeId, String parentKey) {
        String sql = "SELECT COUNT(*) FROM scope_items WHERE scope_id = ?::uuid AND parent_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            ps.setString(2, parentKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeItemStore.countChildrenByParent: %s / %s: %s",
                    scopeId, parentKey, e.getMessage());
        }
        return 0;
    }

    /** Returns a single item by scope and issue key. */
    public Optional<ScopeItem> findByScopeAndIssueKey(String scopeId, String issueKey) {
        String sql = """
                SELECT id, scope_id, issue_key, issue_type, parent_key, grandparent_key,
                       summary, jira_status, synced_at, jira_modified_at,
                       assignee, reporter, sprint_name, sprint_start, sprint_end
                FROM scope_items
                WHERE scope_id = ?::uuid AND issue_key = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            ps.setString(2, issueKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeItemStore.findByScopeAndIssueKey: %s / %s: %s",
                    scopeId, issueKey, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Inserts a single scope item. If the (scope_id, issue_key) pair already exists
     * the row is left unchanged (ON CONFLICT DO NOTHING), so this is safe to call
     * even if a sync has already registered the issue.
     */
    public void insertItem(String scopeId, String issueKey, String issueType,
                           String parentKey, String grandparentKey, String summary) {
        String sql = """
                INSERT INTO scope_items
                    (scope_id, issue_key, issue_type, parent_key, grandparent_key, summary)
                VALUES (?::uuid, ?, ?, ?, ?, ?)
                ON CONFLICT (scope_id, issue_key) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            ps.setString(2, issueKey);
            ps.setString(3, issueType);
            ps.setString(4, parentKey);
            ps.setString(5, grandparentKey);
            ps.setString(6, summary);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("ScopeItemStore.insertItem: %s / %s: %s", scopeId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to insert scope item", e);
        }
    }

    /**
     * Updates only the summary of a scope item in-place.
     * Called after a Jira issue has already been updated so the local DB stays in sync.
     */
    public void updateSummary(String scopeId, String issueKey, String summary) {
        String sql = """
                UPDATE scope_items
                SET summary   = ?,
                    synced_at = now()
                WHERE scope_id = ?::uuid AND issue_key = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, summary);
            ps.setString(2, scopeId);
            ps.setString(3, issueKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("ScopeItemStore.updateSummary: %s / %s: %s", scopeId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to update scope item summary", e);
        }
    }

    /**
     * Updates the live fields of a single item from a fresh Jira fetch.
     * Called by the refresh-on-detail-open flow.
     */
    public void refreshLiveFields(String scopeId, String issueKey,
                                   String summary, String jiraStatus,
                                   java.time.Instant jiraModifiedAt,
                                   String assignee, String reporter,
                                   String sprintName,
                                   java.time.Instant sprintStart,
                                   java.time.Instant sprintEnd) {
        String sql = """
                UPDATE scope_items
                SET summary          = ?,
                    jira_status      = ?,
                    jira_modified_at = ?,
                    assignee         = ?,
                    reporter         = ?,
                    sprint_name      = ?,
                    sprint_start     = ?,
                    sprint_end       = ?,
                    synced_at        = now()
                WHERE scope_id = ?::uuid AND issue_key = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, summary);
            ps.setString(2, jiraStatus);
            setTimestamp(ps, 3, jiraModifiedAt);
            ps.setString(4, assignee);
            ps.setString(5, reporter);
            ps.setString(6, sprintName);
            setTimestamp(ps, 7, sprintStart);
            setTimestamp(ps, 8, sprintEnd);
            ps.setString(9,  scopeId);
            ps.setString(10, issueKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("ScopeItemStore.refreshLiveFields: %s / %s: %s",
                    scopeId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to refresh scope item", e);
        }
    }

    private static void setTimestamp(PreparedStatement ps, int idx, java.time.Instant value)
            throws SQLException {
        if (value != null) {
            ps.setTimestamp(idx, Timestamp.from(value));
        } else {
            ps.setNull(idx, Types.TIMESTAMP_WITH_TIMEZONE);
        }
    }

    private ScopeItem mapRow(ResultSet rs) throws SQLException {
        Timestamp syncedAt       = rs.getTimestamp("synced_at");
        Timestamp jiraModifiedAt = rs.getTimestamp("jira_modified_at");
        Timestamp sprintStart    = rs.getTimestamp("sprint_start");
        Timestamp sprintEnd      = rs.getTimestamp("sprint_end");
        return new ScopeItem(
                rs.getString("id"),
                rs.getString("scope_id"),
                rs.getString("issue_key"),
                rs.getString("issue_type"),
                rs.getString("parent_key"),
                rs.getString("grandparent_key"),
                rs.getString("summary"),
                rs.getString("jira_status"),
                syncedAt       != null ? syncedAt.toInstant()       : null,
                jiraModifiedAt != null ? jiraModifiedAt.toInstant() : null,
                rs.getString("assignee"),
                rs.getString("reporter"),
                rs.getString("sprint_name"),
                sprintStart    != null ? sprintStart.toInstant()    : null,
                sprintEnd      != null ? sprintEnd.toInstant()      : null
        );
    }
}
