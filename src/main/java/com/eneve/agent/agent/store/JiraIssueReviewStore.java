package com.eneve.agent.agent.store;

import com.eneve.agent.model.JiraIssueReview;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class JiraIssueReviewStore {

    private static final Logger LOG = Logger.getLogger(JiraIssueReviewStore.class);

    @Inject
    AgroalDataSource dataSource;

    /**
     * Upserts a review result for a given (scopeId, issueKey) pair.
     * If scopeId is null, upserts based on issueKey alone (standalone review).
     */
    public void upsert(String scopeId, String issueKey, String issueType, String issueSummary,
                       String parentKey, String jiraStatus,
                       int readinessScore, String readinessLabel,
                       int complexityScore, String improvementSummary,
                       String reviewJson, String jobId) {
        // For scope-scoped reviews we first try UPDATE then INSERT to handle the partial unique index.
        // PostgreSQL's ON CONFLICT does not support partial indexes via ON CONSTRAINT for WHERE indexes.
        String sql;
        if (scopeId != null) {
            // Try update first; if no rows updated, insert
            sql = """
                    UPDATE jira_issue_reviews SET
                        issue_type          = ?,
                        issue_summary       = ?,
                        parent_key          = ?,
                        jira_status         = ?,
                        readiness_score     = ?,
                        readiness_label     = ?,
                        complexity_score    = ?,
                        improvement_summary = ?,
                        review_json         = ?::jsonb,
                        job_id              = ?,
                        reviewed_at         = now()
                    WHERE scope_id = ?::uuid AND issue_key = ?
                    """;
        } else {
            sql = """
                    UPDATE jira_issue_reviews SET
                        issue_type          = ?,
                        issue_summary       = ?,
                        parent_key          = ?,
                        jira_status         = ?,
                        readiness_score     = ?,
                        readiness_label     = ?,
                        complexity_score    = ?,
                        improvement_summary = ?,
                        review_json         = ?::jsonb,
                        job_id              = ?,
                        reviewed_at         = now()
                    WHERE scope_id IS NULL AND issue_key = ?
                    """;
        }
        try (Connection conn = dataSource.getConnection()) {
            // Execute UPDATE first
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                ps.setString(idx++, issueType);
                setNullable(ps, idx++, issueSummary);
                setNullable(ps, idx++, parentKey);
                setNullable(ps, idx++, jiraStatus);
                ps.setInt(idx++, readinessScore);
                setNullable(ps, idx++, readinessLabel);
                ps.setInt(idx++, complexityScore);
                setNullable(ps, idx++, improvementSummary);
                setNullable(ps, idx++, reviewJson);
                setNullable(ps, idx++, jobId);
                if (scopeId != null) {
                    ps.setString(idx++, scopeId);
                }
                ps.setString(idx, issueKey);
                updated = ps.executeUpdate();
            }
            // If no row was updated, insert a new one
            if (updated == 0) {
                String insertSql;
                if (scopeId != null) {
                    insertSql = """
                            INSERT INTO jira_issue_reviews
                                (id, scope_id, issue_key, issue_type, issue_summary, parent_key, jira_status,
                                 readiness_score, readiness_label, complexity_score, improvement_summary,
                                 review_json, job_id, reviewed_at)
                            VALUES (gen_random_uuid(), ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
                            """;
                } else {
                    insertSql = """
                            INSERT INTO jira_issue_reviews
                                (id, scope_id, issue_key, issue_type, issue_summary, parent_key, jira_status,
                                 readiness_score, readiness_label, complexity_score, improvement_summary,
                                 review_json, job_id, reviewed_at)
                            VALUES (gen_random_uuid(), NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
                            """;
                }
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    int idx = 1;
                    if (scopeId != null) ps.setString(idx++, scopeId);
                    ps.setString(idx++, issueKey);
                    ps.setString(idx++, issueType);
                    setNullable(ps, idx++, issueSummary);
                    setNullable(ps, idx++, parentKey);
                    setNullable(ps, idx++, jiraStatus);
                    ps.setInt(idx++, readinessScore);
                    setNullable(ps, idx++, readinessLabel);
                    ps.setInt(idx++, complexityScore);
                    setNullable(ps, idx++, improvementSummary);
                    setNullable(ps, idx++, reviewJson);
                    setNullable(ps, idx, jobId);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            LOG.errorf("JiraIssueReviewStore: upsert failed for %s: %s", issueKey, e.getMessage());
            throw new RuntimeException("Failed to upsert review for " + issueKey, e);
        }
    }

    public List<JiraIssueReview> findByScope(String scopeId) {
        String sql = """
                SELECT id, scope_id, issue_key, issue_type, issue_summary, parent_key, jira_status,
                       readiness_score, readiness_label, complexity_score, improvement_summary,
                       review_json, job_id, reviewed_at, created_at
                FROM jira_issue_reviews
                WHERE scope_id = ?::uuid
                ORDER BY issue_key
                """;
        List<JiraIssueReview> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("JiraIssueReviewStore: findByScope failed for %s: %s", scopeId, e.getMessage());
        }
        return results;
    }

    public Optional<JiraIssueReview> findByScopeAndIssueKey(String scopeId, String issueKey) {
        String sql = """
                SELECT id, scope_id, issue_key, issue_type, issue_summary, parent_key, jira_status,
                       readiness_score, readiness_label, complexity_score, improvement_summary,
                       review_json, job_id, reviewed_at, created_at
                FROM jira_issue_reviews
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
            LOG.errorf("JiraIssueReviewStore: findByScopeAndIssueKey failed for %s/%s: %s", scopeId, issueKey, e.getMessage());
        }
        return Optional.empty();
    }

    private JiraIssueReview mapRow(ResultSet rs) throws SQLException {
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        Timestamp createdAt = rs.getTimestamp("created_at");
        int readinessScore = rs.getInt("readiness_score");
        Integer readinessScoreVal = rs.wasNull() ? null : readinessScore;
        int complexityScore = rs.getInt("complexity_score");
        Integer complexityScoreVal = rs.wasNull() ? null : complexityScore;
        return new JiraIssueReview(
                rs.getString("id"),
                rs.getString("scope_id"),
                rs.getString("issue_key"),
                rs.getString("issue_type"),
                rs.getString("issue_summary"),
                rs.getString("parent_key"),
                rs.getString("jira_status"),
                readinessScoreVal,
                rs.getString("readiness_label"),
                complexityScoreVal,
                rs.getString("improvement_summary"),
                rs.getString("review_json"),
                rs.getString("job_id"),
                reviewedAt != null ? reviewedAt.toInstant() : null,
                createdAt != null ? createdAt.toInstant() : null
        );
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) ps.setString(index, value);
        else ps.setNull(index, Types.VARCHAR);
    }
}
