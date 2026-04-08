package com.eneve.agent.servicedesk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed store for {@code service_desk_triage_findings}.
 *
 * <p>One row per Jira issue key — upserted on conflict so re-triaging a ticket
 * updates the existing record rather than creating a duplicate.
 */
@ApplicationScoped
public class ServiceDeskTriageFindingsStore {

    private static final Logger LOG = Logger.getLogger(ServiceDeskTriageFindingsStore.class);

    @Inject AgroalDataSource dataSource;
    @Inject ObjectMapper objectMapper;

    private static final String SELECT_ALL =
            "SELECT id, issue_key, project_key, category, severity, confidence, " +
            "triage_reason, deep_analysis, similar_issue_keys, created_at, updated_at " +
            "FROM service_desk_triage_findings ";

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Inserts or updates a triage finding for the given issue key.
     * On conflict the category, severity, confidence, triage_reason, and updated_at are refreshed.
     *
     * @return the primary key of the upserted row, or -1 on error
     */
    public long upsertTriage(String issueKey, String projectKey, String category,
                             String severity, double confidence, String triageReason) {
        String sql = """
                INSERT INTO service_desk_triage_findings
                    (issue_key, project_key, category, severity, confidence, triage_reason)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (issue_key) DO UPDATE
                    SET project_key   = EXCLUDED.project_key,
                        category      = EXCLUDED.category,
                        severity      = EXCLUDED.severity,
                        confidence    = EXCLUDED.confidence,
                        triage_reason = EXCLUDED.triage_reason,
                        updated_at    = now()
                RETURNING id
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, issueKey);
            ps.setString(2, projectKey != null ? projectKey : "");
            ps.setString(3, category);
            ps.setString(4, severity);
            ps.setDouble(5, confidence);
            ps.setString(6, triageReason);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert triage finding for %s: %s", issueKey, e.getMessage());
        }
        return -1L;
    }

    /**
     * Saves the deep-analysis text and the list of similar issue keys for a finding.
     *
     * @return true if the row was found and updated
     */
    public boolean saveDeepAnalysis(long id, String deepAnalysis, List<String> similarIssueKeys) {
        String similarJson = toJson(similarIssueKeys);
        String sql = """
                UPDATE service_desk_triage_findings
                SET deep_analysis      = ?,
                    similar_issue_keys = ?::jsonb,
                    updated_at         = now()
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deepAnalysis);
            ps.setString(2, similarJson);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to save deep analysis for finding %d: %s", id, e.getMessage());
            return false;
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public Optional<ServiceDeskTriageFinding> findByIssueKey(String issueKey) {
        String sql = SELECT_ALL + "WHERE issue_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, issueKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find triage finding for %s: %s", issueKey, e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<ServiceDeskTriageFinding> findById(long id) {
        String sql = SELECT_ALL + "WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find triage finding by id %d: %s", id, e.getMessage());
        }
        return Optional.empty();
    }

    public List<ServiceDeskTriageFinding> listByProject(String projectKey, int limit, int offset) {
        String sql = SELECT_ALL + "WHERE project_key = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<ServiceDeskTriageFinding> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectKey);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(map(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list triage findings for project %s: %s", projectKey, e.getMessage());
        }
        return results;
    }

    // ── Row mapper ────────────────────────────────────────────────────────────

    private ServiceDeskTriageFinding map(ResultSet rs) throws SQLException {
        return new ServiceDeskTriageFinding(
                rs.getLong("id"),
                rs.getString("issue_key"),
                rs.getString("project_key"),
                rs.getString("category"),
                rs.getString("severity"),
                rs.getObject("confidence") != null ? rs.getDouble("confidence") : null,
                rs.getString("triage_reason"),
                rs.getString("deep_analysis"),
                fromJson(rs.getString("similar_issue_keys")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list != null ? list : List.of());
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
