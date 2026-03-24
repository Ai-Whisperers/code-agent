package com.eneve.agent.agent.store;

import com.eneve.agent.agent.model.WebhookAuditEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-backed store for webhook audit log entries.
 */
@ApplicationScoped
public class WebhookAuditStore {

    private static final Logger LOG = Logger.getLogger(WebhookAuditStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    AgroalDataSource dataSource;

    public void save(WebhookAuditEntry entry) {
        String sql = """
                INSERT INTO webhook_audit_log
                    (platform, event_type, workspace, repo_slug, pr_id, author, action,
                     hooks_executed, payload, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.platform());
            ps.setString(2, entry.eventType());
            setNullableString(ps, 3, entry.workspace());
            setNullableString(ps, 4, entry.repoSlug());
            setNullableString(ps, 5, entry.prId());
            setNullableString(ps, 6, entry.author());
            ps.setString(7, entry.action());
            setNullableString(ps, 8, toJson(entry.hooksExecuted()));
            setNullableString(ps, 9, entry.payload());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to save webhook audit entry for %s/%s [%s]: %s",
                    entry.workspace(), entry.repoSlug(), entry.eventType(), e.getMessage());
        }
    }

    public List<WebhookAuditEntry> listRecent(int limit) {
        String sql = """
                SELECT id, platform, event_type, workspace, repo_slug, pr_id, author, action,
                       hooks_executed, payload, received_at
                FROM webhook_audit_log
                ORDER BY received_at DESC
                LIMIT ?
                """;
        List<WebhookAuditEntry> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list recent webhook audit entries: %s", e.getMessage());
        }
        return results;
    }

    public List<WebhookAuditEntry> listByRepo(String workspace, String repoSlug, int limit) {
        String sql = """
                SELECT id, platform, event_type, workspace, repo_slug, pr_id, author, action,
                       hooks_executed, payload, received_at
                FROM webhook_audit_log
                WHERE workspace = ? AND repo_slug = ?
                ORDER BY received_at DESC
                LIMIT ?
                """;
        List<WebhookAuditEntry> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list webhook audit entries for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private WebhookAuditEntry mapRow(ResultSet rs) throws SQLException {
        Timestamp receivedTs = rs.getTimestamp("received_at");
        return new WebhookAuditEntry(
                rs.getLong("id"),
                rs.getString("platform"),
                rs.getString("event_type"),
                rs.getString("workspace"),
                rs.getString("repo_slug"),
                rs.getString("pr_id"),
                rs.getString("author"),
                rs.getString("action"),
                fromJson(rs.getString("hooks_executed")),
                rs.getString("payload"),
                receivedTs != null ? receivedTs.toInstant() : null
        );
    }

    private static String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }
}
