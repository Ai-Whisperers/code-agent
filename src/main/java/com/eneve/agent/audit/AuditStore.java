package com.eneve.agent.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * PostgreSQL-backed store for the {@code audit_log} table.
 * Write path is invoked fire-and-forget from {@link AuditService}.
 */
@ApplicationScoped
public class AuditStore {

    private static final Logger LOG = Logger.getLogger(AuditStore.class);
    private static final int MAX_LIMIT = 1000;

    @Inject
    AgroalDataSource dataSource;

    public void save(AuditEntry entry) {
        String sql = """
                INSERT INTO audit_log
                    (actor, category, action, resource_type, resource_id, detail, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.actor());
            ps.setString(2, entry.category());
            ps.setString(3, entry.action());
            setNullable(ps, 4, entry.resourceType());
            setNullable(ps, 5, entry.resourceId());
            setNullable(ps, 6, entry.detail());
            ps.setTimestamp(7, entry.occurredAt() != null
                    ? Timestamp.from(entry.occurredAt()) : new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to save audit entry [%s/%s] for %s: %s",
                    entry.category(), entry.action(), entry.actor(), e.getMessage());
        }
    }

    /**
     * Searches audit log entries with optional filters. All filter parameters are optional;
     * pass {@code null} to skip a filter.
     */
    public List<AuditEntry> search(String category, String action, String actor, int limit) {
        int safeLimit = Math.min(limit, MAX_LIMIT);

        StringBuilder sql = new StringBuilder("""
                SELECT id, actor, category, action, resource_type, resource_id, detail, occurred_at
                FROM audit_log
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();

        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            params.add(action);
        }
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor ILIKE ?");
            params.add("%" + actor + "%");
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");
        params.add(safeLimit);

        List<AuditEntry> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Integer iv) ps.setInt(i + 1, iv);
                else ps.setString(i + 1, (String) p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to search audit log: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Returns audit entries for a specific resource (e.g. a job), ordered by
     * {@code occurred_at ASC} so callers get a chronological audit trail.
     */
    public List<AuditEntry> findByResourceId(String resourceId, int limit) {
        int safeLimit = Math.min(limit, MAX_LIMIT);
        String sql = """
                SELECT id, actor, category, action, resource_type, resource_id, detail, occurred_at
                FROM audit_log
                WHERE resource_id = ?
                ORDER BY occurred_at ASC
                LIMIT ?
                """;
        List<AuditEntry> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, resourceId);
            ps.setInt(2, safeLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find audit entries for resource %s: %s", resourceId, e.getMessage());
        }
        return results;
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private AuditEntry mapRow(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("occurred_at");
        return new AuditEntry(
                rs.getLong("id"),
                rs.getString("actor"),
                rs.getString("category"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("detail"),
                ts != null ? ts.toInstant() : null
        );
    }

    private void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }
}
