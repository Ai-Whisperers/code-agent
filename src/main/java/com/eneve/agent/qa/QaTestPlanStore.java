package com.eneve.agent.qa;

import com.eneve.agent.model.QaTestPlan;
import com.eneve.agent.model.QaTestPlanHistory;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC store for {@code qa_test_plans}, {@code qa_scope_test_plans}, and
 * {@code qa_test_plan_history}.
 *
 * <p>Test plans are keyed by {@code issue_key} only — they are shared across
 * all scopes that contain the same feature. The {@code qa_scope_test_plans}
 * join table records which scopes reference a plan.
 */
@ApplicationScoped
public class QaTestPlanStore {

    private static final Logger LOG = Logger.getLogger(QaTestPlanStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ─── Ensure exists ────────────────────────────────────────────────────────

    /**
     * Creates a minimal placeholder row for a feature if one does not exist yet,
     * then links it to the given scope via the join table.
     * Safe to call multiple times — idempotent.
     */
    public void ensureExists(String scopeId, String issueKey) {
        String insertPlan = """
                INSERT INTO qa_test_plans (issue_key)
                VALUES (?)
                ON CONFLICT (issue_key) DO NOTHING
                """;
        String insertLink = """
                INSERT INTO qa_scope_test_plans (scope_id, plan_id)
                SELECT ?::uuid, id FROM qa_test_plans WHERE issue_key = ?
                ON CONFLICT DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(insertPlan)) {
                    ps.setString(1, issueKey);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(insertLink)) {
                    ps.setString(1, scopeId);
                    ps.setString(2, issueKey);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.errorf("QaTestPlanStore.ensureExists: %s / %s: %s", scopeId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to ensure qa_test_plan row", e);
        }
    }

    // ─── Analysis text ────────────────────────────────────────────────────────

    /**
     * Persists the raw markdown analysis from the first Claude call.
     * Resets {@code analysis_edited} to false, increments {@code kpi_regen_count},
     * and updates the spec hash and drift timestamp if the spec changed.
     */
    public void saveAnalysis(String issueKey,
                             String analysisText, String specificationsJson,
                             String specHash, boolean driftDetected) {
        String sql = """
                UPDATE qa_test_plans
                SET analysis_text    = ?,
                    specifications   = ?::jsonb,
                    analysis_edited  = FALSE,
                    generated_at     = now(),
                    kpi_spec_hash    = ?,
                    kpi_drift_detected_at = CASE
                        WHEN ? AND kpi_drift_detected_at IS NULL THEN now()
                        ELSE kpi_drift_detected_at
                    END,
                    kpi_regen_count  = kpi_regen_count + 1
                WHERE issue_key = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, analysisText);
            ps.setString(2, specificationsJson);
            ps.setString(3, specHash);
            ps.setBoolean(4, driftDetected);
            ps.setString(5, issueKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("QaTestPlanStore.saveAnalysis: %s: %s", issueKey, e.getMessage());
            throw new RuntimeException("Failed to save analysis", e);
        }
    }

    /**
     * Persists a manual edit to the analysis text.
     * Sets {@code analysis_edited = true} and increments {@code kpi_analysis_edit_count}.
     */
    public void updateAnalysisText(String issueKey, String analysisText) {
        String sql = """
                UPDATE qa_test_plans
                SET analysis_text           = ?,
                    analysis_edited         = TRUE,
                    kpi_analysis_edit_count = kpi_analysis_edit_count + 1
                WHERE issue_key = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, analysisText);
            ps.setString(2, issueKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("QaTestPlanStore.updateAnalysisText: %s: %s", issueKey, e.getMessage());
            throw new RuntimeException("Failed to update analysis text", e);
        }
    }

    // ─── Plan JSON + KPIs ─────────────────────────────────────────────────────

    /**
     * Persists the formatted featureTestPlan JSON and all extracted KPI columns.
     */
    public void savePlanJson(String issueKey, String planJson,
                             Integer storyCount, Integer behaviourTcCount, Integer capabilityTcCount,
                             Integer riskCount, Integer openClarifications, BigDecimal coveragePct,
                             Integer highRisks, Integer gapsCount, String readiness) {
        String sql = """
                UPDATE qa_test_plans
                SET plan_json                = ?::jsonb,
                    kpi_story_count          = ?,
                    kpi_behaviour_tc_count   = ?,
                    kpi_capability_tc_count  = ?,
                    kpi_risk_count           = ?,
                    kpi_open_clarifications  = ?,
                    kpi_coverage_pct         = ?,
                    kpi_high_risks           = ?,
                    kpi_gaps_count           = ?,
                    kpi_readiness            = ?
                WHERE issue_key = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planJson);
            setNullableInt(ps, 2, storyCount);
            setNullableInt(ps, 3, behaviourTcCount);
            setNullableInt(ps, 4, capabilityTcCount);
            setNullableInt(ps, 5, riskCount);
            setNullableInt(ps, 6, openClarifications);
            if (coveragePct != null) ps.setBigDecimal(7, coveragePct);
            else ps.setNull(7, Types.NUMERIC);
            setNullableInt(ps, 8, highRisks);
            setNullableInt(ps, 9, gapsCount);
            ps.setString(10, readiness);
            ps.setString(11, issueKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("QaTestPlanStore.savePlanJson: %s: %s", issueKey, e.getMessage());
            throw new RuntimeException("Failed to save plan JSON", e);
        }
    }

    // ─── History ──────────────────────────────────────────────────────────────

    public void insertHistory(String planId, String issueKey,
                              Integer behaviourTcCount, Integer capabilityTcCount,
                              Integer riskCount, Integer openClarifications, BigDecimal coveragePct,
                              Integer highRisks, Integer gapsCount, String readiness,
                              String specHash, String trigger) {
        String sql = """
                INSERT INTO qa_test_plan_history
                    (plan_id, issue_key,
                     kpi_behaviour_tc_count, kpi_capability_tc_count, kpi_risk_count,
                     kpi_open_clarifications, kpi_coverage_pct, kpi_high_risks,
                     kpi_gaps_count, kpi_readiness, kpi_spec_hash, trigger)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            ps.setString(2, issueKey);
            setNullableInt(ps, 3, behaviourTcCount);
            setNullableInt(ps, 4, capabilityTcCount);
            setNullableInt(ps, 5, riskCount);
            setNullableInt(ps, 6, openClarifications);
            if (coveragePct != null) ps.setBigDecimal(7, coveragePct);
            else ps.setNull(7, Types.NUMERIC);
            setNullableInt(ps, 8, highRisks);
            setNullableInt(ps, 9, gapsCount);
            ps.setString(10, readiness);
            ps.setString(11, specHash);
            ps.setString(12, trigger);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("QaTestPlanStore.insertHistory: %s: %s", issueKey, e.getMessage());
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    /** Finds a test plan by its UUID primary key. */
    public java.util.Optional<QaTestPlan> findById(String id) {
        String sql = """
                SELECT id, issue_key, analysis_text, plan_json, specifications,
                       generated_at, generated_by, analysis_edited,
                       kpi_story_count, kpi_behaviour_tc_count, kpi_capability_tc_count,
                       kpi_risk_count, kpi_open_clarifications, kpi_coverage_pct,
                       kpi_high_risks, kpi_gaps_count, kpi_readiness,
                       kpi_spec_hash, kpi_drift_detected_at, kpi_regen_count, kpi_analysis_edit_count,
                       jira_issue_key, xray_sync_status, xray_synced_at
                FROM qa_test_plans
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return java.util.Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("QaTestPlanStore.findById: %s: %s", id, e.getMessage());
        }
        return java.util.Optional.empty();
    }

    /** Sets the Jira issue key on a test plan (for Xray sync). */
    public void updateJiraKey(String issueKey, String jiraIssueKey) {
        String sql = "UPDATE qa_test_plans SET jira_issue_key = ? WHERE issue_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jiraIssueKey);
            ps.setString(2, issueKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("QaTestPlanStore.updateJiraKey: %s: %s", issueKey, e.getMessage());
            throw new RuntimeException("Failed to update jira_issue_key", e);
        }
    }

    public Optional<QaTestPlan> findByKey(String issueKey) {
        String sql = """
                SELECT id, issue_key, analysis_text, plan_json, specifications,
                       generated_at, generated_by, analysis_edited,
                       kpi_story_count, kpi_behaviour_tc_count, kpi_capability_tc_count,
                       kpi_risk_count, kpi_open_clarifications, kpi_coverage_pct,
                       kpi_high_risks, kpi_gaps_count, kpi_readiness,
                       kpi_spec_hash, kpi_drift_detected_at, kpi_regen_count, kpi_analysis_edit_count,
                       jira_issue_key, xray_sync_status, xray_synced_at
                FROM qa_test_plans
                WHERE issue_key = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, issueKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("QaTestPlanStore.findByKey: %s: %s", issueKey, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns all test plan rows whose features appear in the given scope,
     * joined through {@code qa_scope_test_plans}.
     */
    public List<QaTestPlan> findAllByScope(String scopeId) {
        String sql = """
                SELECT p.id, p.issue_key, p.analysis_text, p.plan_json, p.specifications,
                       p.generated_at, p.generated_by, p.analysis_edited,
                       p.kpi_story_count, p.kpi_behaviour_tc_count, p.kpi_capability_tc_count,
                       p.kpi_risk_count, p.kpi_open_clarifications, p.kpi_coverage_pct,
                       p.kpi_high_risks, p.kpi_gaps_count, p.kpi_readiness,
                       p.kpi_spec_hash, p.kpi_drift_detected_at, p.kpi_regen_count, p.kpi_analysis_edit_count,
                       p.jira_issue_key, p.xray_sync_status, p.xray_synced_at
                FROM qa_test_plans p
                JOIN qa_scope_test_plans sp ON sp.plan_id = p.id
                WHERE sp.scope_id = ?::uuid
                ORDER BY p.issue_key
                """;
        List<QaTestPlan> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("QaTestPlanStore.findAllByScope: %s: %s", scopeId, e.getMessage());
        }
        return results;
    }

    /**
     * Returns every test plan row, ordered by most-recently generated first.
     * Used by the global test-plans list page.
     */
    public List<QaTestPlan> findAll() {
        String sql = """
                SELECT id, issue_key, analysis_text, plan_json, specifications,
                       generated_at, generated_by, analysis_edited,
                       kpi_story_count, kpi_behaviour_tc_count, kpi_capability_tc_count,
                       kpi_risk_count, kpi_open_clarifications, kpi_coverage_pct,
                       kpi_high_risks, kpi_gaps_count, kpi_readiness,
                       kpi_spec_hash, kpi_drift_detected_at, kpi_regen_count, kpi_analysis_edit_count,
                       jira_issue_key, xray_sync_status, xray_synced_at
                FROM qa_test_plans
                ORDER BY generated_at DESC NULLS LAST, issue_key
                """;
        List<QaTestPlan> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) results.add(mapRow(rs));
        } catch (SQLException e) {
            LOG.errorf("QaTestPlanStore.findAll: %s", e.getMessage());
        }
        return results;
    }

    public List<QaTestPlanHistory> findHistory(String planId) {
        String sql = """
                SELECT id, plan_id, issue_key, snapshot_at,
                       kpi_behaviour_tc_count, kpi_capability_tc_count, kpi_risk_count,
                       kpi_open_clarifications, kpi_coverage_pct, kpi_high_risks,
                       kpi_gaps_count, kpi_readiness, kpi_spec_hash, trigger
                FROM qa_test_plan_history
                WHERE plan_id = ?::uuid
                ORDER BY snapshot_at DESC
                """;
        List<QaTestPlanHistory> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapHistoryRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("QaTestPlanStore.findHistory: %s: %s", planId, e.getMessage());
        }
        return results;
    }

    // ─── Mapping ──────────────────────────────────────────────────────────────

    private QaTestPlan mapRow(ResultSet rs) throws SQLException {
        Timestamp generatedAt = rs.getTimestamp("generated_at");
        Timestamp driftAt = rs.getTimestamp("kpi_drift_detected_at");
        BigDecimal coveragePct = rs.getBigDecimal("kpi_coverage_pct");
        return new QaTestPlan(
                rs.getString("id"),
                rs.getString("issue_key"),
                rs.getString("analysis_text"),
                rs.getString("plan_json"),
                rs.getString("specifications"),
                generatedAt != null ? generatedAt.toInstant() : null,
                rs.getString("generated_by"),
                rs.getBoolean("analysis_edited"),
                getNullableInt(rs, "kpi_story_count"),
                getNullableInt(rs, "kpi_behaviour_tc_count"),
                getNullableInt(rs, "kpi_capability_tc_count"),
                getNullableInt(rs, "kpi_risk_count"),
                getNullableInt(rs, "kpi_open_clarifications"),
                coveragePct,
                getNullableInt(rs, "kpi_high_risks"),
                getNullableInt(rs, "kpi_gaps_count"),
                rs.getString("kpi_readiness"),
                rs.getString("kpi_spec_hash"),
                driftAt != null ? driftAt.toInstant() : null,
                rs.getInt("kpi_regen_count"),
                rs.getInt("kpi_analysis_edit_count"),
                rs.getString("jira_issue_key"),
                rs.getString("xray_sync_status"),
                rs.getTimestamp("xray_synced_at") != null ? rs.getTimestamp("xray_synced_at").toInstant() : null
        );
    }

    private QaTestPlanHistory mapHistoryRow(ResultSet rs) throws SQLException {
        Timestamp snapshotAt = rs.getTimestamp("snapshot_at");
        BigDecimal coveragePct = rs.getBigDecimal("kpi_coverage_pct");
        return new QaTestPlanHistory(
                rs.getString("id"),
                rs.getString("plan_id"),
                rs.getString("issue_key"),
                snapshotAt != null ? snapshotAt.toInstant() : Instant.now(),
                getNullableInt(rs, "kpi_behaviour_tc_count"),
                getNullableInt(rs, "kpi_capability_tc_count"),
                getNullableInt(rs, "kpi_risk_count"),
                getNullableInt(rs, "kpi_open_clarifications"),
                coveragePct,
                getNullableInt(rs, "kpi_high_risks"),
                getNullableInt(rs, "kpi_gaps_count"),
                rs.getString("kpi_readiness"),
                rs.getString("kpi_spec_hash"),
                rs.getString("trigger")
        );
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void setNullableInt(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value != null) ps.setInt(idx, value);
        else ps.setNull(idx, Types.INTEGER);
    }

    private Integer getNullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
