package com.eneve.agent.qa;

import com.eneve.agent.model.QaTestCase;
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
 * JDBC store for {@code qa_test_cases}.
 */
@ApplicationScoped
public class QaTestCaseStore {

    private static final Logger LOG = Logger.getLogger(QaTestCaseStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ─── Write operations ─────────────────────────────────────────────────────

    /**
     * Bulk-inserts test cases for a plan. Existing rows with the same
     * {@code (plan_id, test_case_id)} are replaced.
     */
    public void insertBatch(String planId, String featureKey, List<QaTestCase> cases) {
        String sql = """
                INSERT INTO qa_test_cases
                    (plan_id, feature_key, story_key, test_case_id, title, description,
                     pre_conditions, test_steps, expected_results,
                     test_case_type, priority, status, estimated_duration,
                     kpi_step_count, kpi_estimated_mins, kpi_precondition_count,
                     kpi_execution_count, kpi_automation_status,
                     jira_issue_key, xray_sync_status)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (plan_id, test_case_id) DO UPDATE SET
                    title              = EXCLUDED.title,
                    description        = EXCLUDED.description,
                    pre_conditions     = EXCLUDED.pre_conditions,
                    test_steps         = EXCLUDED.test_steps,
                    expected_results   = EXCLUDED.expected_results,
                    test_case_type     = EXCLUDED.test_case_type,
                    priority           = EXCLUDED.priority,
                    estimated_duration = EXCLUDED.estimated_duration,
                    kpi_step_count     = EXCLUDED.kpi_step_count,
                    kpi_estimated_mins = EXCLUDED.kpi_estimated_mins,
                    kpi_precondition_count = EXCLUDED.kpi_precondition_count,
                    generated_at       = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (QaTestCase tc : cases) {
                ps.setString(1, planId);
                ps.setString(2, featureKey);
                ps.setString(3, tc.storyKey());
                ps.setString(4, tc.testCaseId());
                ps.setString(5, tc.title());
                setNullable(ps, 6, tc.description());
                ps.setString(7, tc.preConditions());
                ps.setString(8, tc.testSteps());
                ps.setString(9, tc.expectedResults());
                ps.setString(10, tc.testCaseType());
                ps.setString(11, tc.priority());
                ps.setString(12, tc.status() != null ? tc.status() : "Open");
                setNullable(ps, 13, tc.estimatedDuration());
                setNullableInt(ps, 14, tc.kpiStepCount());
                setNullableInt(ps, 15, tc.kpiEstimatedMins());
                setNullableInt(ps, 16, tc.kpiPreconditionCount());
                ps.setInt(17, 0);
                ps.setString(18, tc.kpiAutomationStatus() != null ? tc.kpiAutomationStatus() : "manual");
                setNullable(ps, 19, tc.jiraIssueKey());
                ps.setString(20, tc.xraySyncStatus() != null ? tc.xraySyncStatus() : "pending");
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOG.errorf("QaTestCaseStore.insertBatch: planId=%s: %s", planId, e.getMessage());
            throw new RuntimeException("Failed to insert test cases", e);
        }
    }

    /** Deletes all test cases for a plan (used before regeneration). */
    public void deleteByPlan(String planId) {
        String sql = "DELETE FROM qa_test_cases WHERE plan_id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("QaTestCaseStore.deleteByPlan: planId=%s: %s", planId, e.getMessage());
            throw new RuntimeException("Failed to delete test cases", e);
        }
    }

    /** Updates the status of a single test case. */
    public void updateStatus(String id, String status) {
        String sql = "UPDATE qa_test_cases SET status = ? WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("QaTestCaseStore.updateStatus: id=%s: %s", id, e.getMessage());
            throw new RuntimeException("Failed to update test case status", e);
        }
    }

    /** Sets the Jira issue key on a test case after Xray sync. */
    public void updateJiraKey(String id, String jiraIssueKey) {
        String sql = "UPDATE qa_test_cases SET jira_issue_key = ? WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jiraIssueKey);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("QaTestCaseStore.updateJiraKey: id=%s: %s", id, e.getMessage());
            throw new RuntimeException("Failed to update Jira key", e);
        }
    }

    /**
     * Sets the Jira issue key, marks the test case as synced, and records the sync timestamp.
     * Used after a successful "Upload to Jira" sync.
     */
    public void updateJiraKeyAndSync(String id, String jiraIssueKey) {
        String sql = """
                UPDATE qa_test_cases
                SET jira_issue_key    = ?,
                    xray_sync_status  = 'synced',
                    xray_synced_at    = now()
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jiraIssueKey);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("QaTestCaseStore.updateJiraKeyAndSync: id=%s: %s", id, e.getMessage());
            throw new RuntimeException("Failed to update Jira key and sync status", e);
        }
    }

    /** Marks an existing synced test case as updated (refreshes xray_synced_at). */
    public void markSynced(String id) {
        String sql = """
                UPDATE qa_test_cases
                SET xray_sync_status = 'synced',
                    xray_synced_at   = now()
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("QaTestCaseStore.markSynced: id=%s: %s", id, e.getMessage());
            throw new RuntimeException("Failed to mark test case as synced", e);
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    /** Returns all test cases for a plan, ordered by story_key then test_case_id. */
    public List<QaTestCase> findByPlan(String planId) {
        String sql = """
                SELECT id, plan_id, feature_key, story_key, test_case_id, title, description,
                       pre_conditions, test_steps, expected_results,
                       test_case_type, priority, status, estimated_duration,
                       kpi_step_count, kpi_estimated_mins, kpi_precondition_count,
                       kpi_execution_count, kpi_last_result, kpi_last_executed_at,
                       kpi_automation_status, jira_issue_key, xray_sync_status, xray_synced_at,
                       generated_at
                FROM qa_test_cases
                WHERE plan_id = ?::uuid
                ORDER BY story_key, test_case_id
                """;
        List<QaTestCase> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("QaTestCaseStore.findByPlan: planId=%s: %s", planId, e.getMessage());
        }
        return results;
    }

    /** Returns test cases for a specific story within a plan. */
    public List<QaTestCase> findByStory(String planId, String storyKey) {
        String sql = """
                SELECT id, plan_id, feature_key, story_key, test_case_id, title, description,
                       pre_conditions, test_steps, expected_results,
                       test_case_type, priority, status, estimated_duration,
                       kpi_step_count, kpi_estimated_mins, kpi_precondition_count,
                       kpi_execution_count, kpi_last_result, kpi_last_executed_at,
                       kpi_automation_status, jira_issue_key, xray_sync_status, xray_synced_at,
                       generated_at
                FROM qa_test_cases
                WHERE plan_id = ?::uuid AND story_key = ?
                ORDER BY test_case_id
                """;
        List<QaTestCase> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            ps.setString(2, storyKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("QaTestCaseStore.findByStory: planId=%s storyKey=%s: %s", planId, storyKey, e.getMessage());
        }
        return results;
    }

    /**
     * Looks up a test case by its Jira issue key within a plan.
     * Used during ETR import to avoid inserting duplicate rows.
     */
    public Optional<QaTestCase> findByJiraKey(String planId, String jiraKey) {
        String sql = """
                SELECT id, plan_id, feature_key, story_key, test_case_id, title, description,
                       pre_conditions, test_steps, expected_results,
                       test_case_type, priority, status, estimated_duration,
                       kpi_step_count, kpi_estimated_mins, kpi_precondition_count,
                       kpi_execution_count, kpi_last_result, kpi_last_executed_at,
                       kpi_automation_status, jira_issue_key, xray_sync_status, xray_synced_at,
                       generated_at
                FROM qa_test_cases
                WHERE plan_id = ?::uuid AND jira_issue_key = ?
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            ps.setString(2, jiraKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("QaTestCaseStore.findByJiraKey: planId=%s jiraKey=%s: %s", planId, jiraKey, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Links an AI-generated test case to an ETR Jira issue and marks it as matched.
     * Also overwrites title, description, testSteps, and priority from the ETR issue.
     */
    public void linkJiraKeyAndMatch(String id, String jiraIssueKey,
                                    String title, String description,
                                    String testSteps, String priority) {
        String sql = """
                UPDATE qa_test_cases
                SET jira_issue_key   = ?,
                    xray_sync_status = 'matched',
                    xray_synced_at   = now(),
                    title            = ?,
                    description      = ?,
                    test_steps       = ?::jsonb,
                    priority         = ?
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jiraIssueKey);
            ps.setString(2, title);
            ps.setString(3, description);
            ps.setString(4, testSteps);
            ps.setString(5, priority);
            ps.setString(6, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("QaTestCaseStore.linkJiraKeyAndMatch: id=%s: %s", id, e.getMessage());
            throw new RuntimeException("Failed to link Jira key", e);
        }
    }

    /** Returns the count of test cases for a plan (fast check without loading all rows). */
    public int countByPlan(String planId) {
        String sql = "SELECT COUNT(*) FROM qa_test_cases WHERE plan_id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOG.errorf("QaTestCaseStore.countByPlan: planId=%s: %s", planId, e.getMessage());
        }
        return 0;
    }

    // ─── Mapping ──────────────────────────────────────────────────────────────

    private QaTestCase mapRow(ResultSet rs) throws SQLException {
        Timestamp lastExecutedAt = rs.getTimestamp("kpi_last_executed_at");
        Timestamp xraySyncedAt = rs.getTimestamp("xray_synced_at");
        Timestamp generatedAt = rs.getTimestamp("generated_at");
        return new QaTestCase(
                rs.getString("id"),
                rs.getString("plan_id"),
                rs.getString("feature_key"),
                rs.getString("story_key"),
                rs.getString("test_case_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("pre_conditions"),
                rs.getString("test_steps"),
                rs.getString("expected_results"),
                rs.getString("test_case_type"),
                rs.getString("priority"),
                rs.getString("status"),
                rs.getString("estimated_duration"),
                getNullableInt(rs, "kpi_step_count"),
                getNullableInt(rs, "kpi_estimated_mins"),
                getNullableInt(rs, "kpi_precondition_count"),
                rs.getInt("kpi_execution_count"),
                rs.getString("kpi_last_result"),
                lastExecutedAt != null ? lastExecutedAt.toInstant() : null,
                rs.getString("kpi_automation_status"),
                rs.getString("jira_issue_key"),
                rs.getString("xray_sync_status"),
                xraySyncedAt != null ? xraySyncedAt.toInstant() : null,
                generatedAt != null ? generatedAt.toInstant() : Instant.now()
        );
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void setNullable(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value != null) ps.setString(idx, value);
        else ps.setNull(idx, Types.VARCHAR);
    }

    private void setNullableInt(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value != null) ps.setInt(idx, value);
        else ps.setNull(idx, Types.INTEGER);
    }

    private Integer getNullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
