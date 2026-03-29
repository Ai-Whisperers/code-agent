package com.eneve.agent.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * PostgreSQL-backed store for audit events.
 * Provides persistence and querying capabilities for audit trail.
 */
@ApplicationScoped
public class AuditStore {

    private static final Logger LOG = Logger.getLogger(AuditStore.class);

    @Inject
    AgroalDataSource dataSource;

    /**
     * Saves an audit event to the database.
     */
    public void save(AuditEvent event) {
        String sql = """
                INSERT INTO audit_events
                    (event_type, user_id, entity_type, entity_id, action,
                     details, ip_address, user_agent, session_id, success,
                     error_message, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setNullableString(ps, 1, event.eventType());
            setNullableString(ps, 2, event.userId());
            setNullableString(ps, 3, event.entityType());
            setNullableString(ps, 4, event.entityId());
            setNullableString(ps, 5, event.action());
            setNullableString(ps, 6, event.details());
            setNullableString(ps, 7, event.ipAddress());
            setNullableString(ps, 8, event.userAgent());
            setNullableString(ps, 9, event.sessionId());
            ps.setBoolean(10, event.success());
            setNullableString(ps, 11, event.errorMessage());
            ps.setTimestamp(12, Timestamp.from(event.timestamp() != null ? event.timestamp() : Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to store audit event: %s", e.getMessage());
            throw new RuntimeException("Failed to store audit event", e);
        }
    }

    /**
     * Finds audit events by entity type and ID.
     */
    public List<AuditEvent> findByEntity(String entityType, String entityId) {
        String sql = """
                SELECT id, event_type, user_id, entity_type, entity_id, action,
                       details, ip_address, user_agent, session_id, success,
                       error_message, timestamp
                FROM audit_events 
                WHERE entity_type = ? AND entity_id = ?
                ORDER BY timestamp DESC
                """;
        List<AuditEvent> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setString(2, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query audit events by entity %s/%s: %s", entityType, entityId, e.getMessage());
            throw new RuntimeException("Failed to query audit events", e);
        }
        return results;
    }

    /**
     * Finds audit events by user ID.
     */
    public List<AuditEvent> findByUserId(String userId, int limit, int offset) {
        String sql = """
                SELECT id, event_type, user_id, entity_type, entity_id, action,
                       details, ip_address, user_agent, session_id, success,
                       error_message, timestamp
                FROM audit_events 
                WHERE user_id = ?
                ORDER BY timestamp DESC
                LIMIT ? OFFSET ?
                """;
        List<AuditEvent> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query audit events by userId %s: %s", userId, e.getMessage());
            throw new RuntimeException("Failed to query audit events", e);
        }
        return results;
    }

    /**
     * Finds audit events by event type.
     */
    public List<AuditEvent> findByEventType(String eventType, Instant from, Instant to, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_type, user_id, entity_type, entity_id, action,
                       details, ip_address, user_agent, session_id, success,
                       error_message, timestamp
                FROM audit_events 
                WHERE event_type = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(eventType);
        
        if (from != null) {
            sql.append(" AND timestamp >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND timestamp <= ?");
            params.add(Timestamp.from(to));
        }
        
        sql.append(" ORDER BY timestamp DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<AuditEvent> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query audit events by event type %s: %s", eventType, e.getMessage());
            throw new RuntimeException("Failed to query audit events", e);
        }
        return results;
    }

    /**
     * Gets recent audit events with optional filtering.
     */
    public List<AuditEvent> getRecentEvents(int limit, int offset, String eventType, 
                                          String userId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_type, user_id, entity_type, entity_id, action,
                       details, ip_address, user_agent, session_id, success,
                       error_message, timestamp
                FROM audit_events WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, eventType, userId, from, to);
        sql.append(" ORDER BY timestamp DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<AuditEvent> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query recent audit events: %s", e.getMessage());
            throw new RuntimeException("Failed to query audit events", e);
        }
        return results;
    }

    /**
     * Gets audit statistics summary.
     */
    public Map<String, Object> getStatistics(Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT 
                    COUNT(*) AS total_events,
                    COUNT(DISTINCT user_id) AS unique_users,
                    COUNT(CASE WHEN success = true THEN 1 END) AS successful_events,
                    COUNT(CASE WHEN success = false THEN 1 END) AS failed_events,
                    COUNT(DISTINCT event_type) AS unique_event_types,
                    COUNT(DISTINCT entity_type) AS unique_entity_types
                FROM audit_events WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        appendTimeFilters(sql, params, from, to);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> stats = new HashMap<>();
                    stats.put("totalEvents", rs.getLong("total_events"));
                    stats.put("uniqueUsers", rs.getLong("unique_users"));
                    stats.put("successfulEvents", rs.getLong("successful_events"));
                    stats.put("failedEvents", rs.getLong("failed_events"));
                    stats.put("uniqueEventTypes", rs.getLong("unique_event_types"));
                    stats.put("uniqueEntityTypes", rs.getLong("unique_entity_types"));
                    return stats;
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query audit statistics: %s", e.getMessage());
            throw new RuntimeException("Failed to query audit statistics", e);
        }
        return new HashMap<>();
    }

    /**
     * Counts events by event type for the given time range.
     */
    public Map<String, Long> getEventTypeCounts(Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT event_type, COUNT(*) as count
                FROM audit_events WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        appendTimeFilters(sql, params, from, to);
        sql.append(" GROUP BY event_type ORDER BY count DESC");

        Map<String, Long> results = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.put(rs.getString("event_type"), rs.getLong("count"));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query event type counts: %s", e.getMessage());
            throw new RuntimeException("Failed to query event type counts", e);
        }
        return results;
    }

    private AuditEvent mapRow(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("timestamp");
        return new AuditEvent(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getString("user_id"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("action"),
                rs.getString("details"),
                rs.getString("ip_address"),
                rs.getString("user_agent"),
                rs.getString("session_id"),
                rs.getBoolean("success"),
                rs.getString("error_message"),
                ts != null ? ts.toInstant() : null
        );
    }

    private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }

    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object param = params.get(i);
            if (param instanceof String) {
                ps.setString(i + 1, (String) param);
            } else if (param instanceof Integer) {
                ps.setInt(i + 1, (Integer) param);
            } else if (param instanceof Long) {
                ps.setLong(i + 1, (Long) param);
            } else if (param instanceof Timestamp) {
                ps.setTimestamp(i + 1, (Timestamp) param);
            } else if (param instanceof Boolean) {
                ps.setBoolean(i + 1, (Boolean) param);
            } else {
                ps.setObject(i + 1, param);
            }
        }
    }

    private void appendFilters(StringBuilder sql, List<Object> params, String eventType, 
                              String userId, Instant from, Instant to) {
        if (eventType != null) {
            sql.append(" AND event_type = ?");
            params.add(eventType);
        }
        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        appendTimeFilters(sql, params, from, to);
    }

    private void appendTimeFilters(StringBuilder sql, List<Object> params, Instant from, Instant to) {
        if (from != null) {
            sql.append(" AND timestamp >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND timestamp <= ?");
            params.add(Timestamp.from(to));
        }
    }
}