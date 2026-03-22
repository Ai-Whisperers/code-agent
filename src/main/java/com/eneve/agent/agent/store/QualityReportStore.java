package com.eneve.agent.agent.store;

import com.eneve.agent.agent.model.QualityReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * PostgreSQL-backed store for {@link QualityReport} snapshots.
 * Reports are serialised to JSONB in the {@code quality_reports} table.
 */
@ApplicationScoped
public class QualityReportStore {

    private static final Logger LOG = Logger.getLogger(QualityReportStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Inject
    AgroalDataSource dataSource;

    /**
     * Persists a report and returns the generated {@code report_id}.
     */
    public String save(QualityReport report) {
        String reportId = report.reportId() != null ? report.reportId() : UUID.randomUUID().toString();
        String sql = """
                INSERT INTO quality_reports
                    (report_id, workspace, repo_slug, branch, report_data, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, now())
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, reportId);
            ps.setString(2, report.workspace());
            ps.setString(3, report.repoSlug());
            ps.setString(4, report.branch());
            ps.setString(5, toJson(report));
            ps.executeUpdate();
            LOG.infof("QualityReportStore: saved report %s for %s/%s branch=%s score=%.4f",
                    reportId, report.workspace(), report.repoSlug(), report.branch(), report.score());
        } catch (SQLException e) {
            LOG.errorf("QualityReportStore: failed to save report: %s", e.getMessage());
        }
        return reportId;
    }

    /**
     * Returns the latest report for a specific branch, or empty if none exists.
     */
    public Optional<QualityReport> findLatest(String workspace, String repoSlug, String branch) {
        String sql = """
                SELECT report_data
                FROM quality_reports
                WHERE workspace = ? AND repo_slug = ? AND branch = ?
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, branch);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(fromJson(rs.getString("report_data")));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("QualityReportStore: failed to query latest for %s/%s branch=%s: %s",
                    workspace, repoSlug, branch, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns historical reports for a specific branch, newest first, limited to {@code limit} rows.
     */
    public List<QualityReport> findHistory(String workspace, String repoSlug, String branch, int limit) {
        String sql = """
                SELECT report_data
                FROM quality_reports
                WHERE workspace = ? AND repo_slug = ? AND branch = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;
        List<QualityReport> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, branch);
            ps.setInt(4, limit > 0 ? limit : 30);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    QualityReport report = fromJson(rs.getString("report_data"));
                    if (report != null) results.add(report);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("QualityReportStore: failed to query history for %s/%s branch=%s: %s",
                    workspace, repoSlug, branch, e.getMessage());
        }
        return results;
    }

    /**
     * Returns the latest report for each distinct branch for a repository.
     * Useful for multi-branch comparison views.
     */
    public Map<String, QualityReport> findLatestPerBranch(String workspace, String repoSlug) {
        String sql = """
                SELECT DISTINCT ON (branch) branch, report_data
                FROM quality_reports
                WHERE workspace = ? AND repo_slug = ?
                ORDER BY branch, created_at DESC
                """;
        Map<String, QualityReport> results = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String branch = rs.getString("branch");
                    QualityReport report = fromJson(rs.getString("report_data"));
                    if (report != null) results.put(branch, report);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("QualityReportStore: failed to query latest-per-branch for %s/%s: %s",
                    workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    // ─── Serialisation helpers ────────────────────────────────────────────

    private String toJson(QualityReport report) {
        try {
            return MAPPER.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            LOG.warnf("QualityReportStore: failed to serialise report: %s", e.getMessage());
            return "{}";
        }
    }

    private QualityReport fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, QualityReport.class);
        } catch (JsonProcessingException e) {
            LOG.warnf("QualityReportStore: failed to deserialise report: %s", e.getMessage());
            return null;
        }
    }
}
