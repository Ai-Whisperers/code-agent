package com.eneve.agent.agent.store;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregation queries for AI suggestion acceptance rate metrics.
 *
 * <p>Classifies each {@code agent_comments} finding into one of three buckets:
 * <ul>
 *   <li><b>accepted</b>  – {@code resolved = true} with no {@code comment_feedback} row</li>
 *   <li><b>rejected</b>  – {@code comment_feedback.feedback = 'false_positive'}</li>
 *   <li><b>ignored</b>   – {@code resolved = false} with no {@code comment_feedback} row</li>
 * </ul>
 *
 * <p>Note: {@code helpful} and {@code disagree} feedback values exist in the schema but are
 * never written by any current code path, so they are not included as separate buckets.
 *
 * <p>The join path to job type and PR author is:
 * {@code agent_comments.review_job_id → jobs/job_history.job_id}.
 */
@ApplicationScoped
public class AiAcceptanceStore {

    private static final Logger LOG = Logger.getLogger(AiAcceptanceStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ─── Overall summary ─────────────────────────────────────────────────────

    /**
     * Returns overall accepted/rejected/ignored counts and percentages for the repo,
     * plus a breakdown grouped by {@code repo_slug}, {@code job_type}, or {@code pr_author}.
     *
     * @param workspace workspace slug
     * @param repoSlug  repository slug
     * @param days      rolling window in days (1–365)
     * @param groupBy   {@code "repo"}, {@code "jobType"}, or {@code "author"}
     */
    public Map<String, Object> getSummary(String workspace, String repoSlug, int days, String groupBy) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        Map<String, Object> totals = getTotals(workspace, repoSlug, since);
        List<Map<String, Object>> breakdown = getBreakdown(workspace, repoSlug, since, groupBy);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspace",  workspace);
        result.put("repoSlug",   repoSlug);
        result.put("periodDays", days);
        result.put("groupBy",    groupBy);
        result.putAll(totals);
        result.put("breakdown",  breakdown);
        return result;
    }

    // ─── Trend ───────────────────────────────────────────────────────────────

    /**
     * Returns weekly-bucketed acceptance rate (accepted / total) for the repo.
     *
     * @param workspace workspace slug
     * @param repoSlug  repository slug
     * @param days      rolling window in days (1–365)
     */
    public List<Map<String, Object>> getTrend(String workspace, String repoSlug, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        String sql = """
                WITH all_jobs AS (
                    SELECT job_id, job_type, pr_author
                    FROM jobs
                    WHERE workspace = ? AND repo_slug = ?
                    UNION ALL
                    SELECT job_id, job_type, pr_author
                    FROM job_history
                    WHERE workspace = ? AND repo_slug = ?
                ),
                findings AS (
                    SELECT
                        ac.comment_id,
                        ac.resolved,
                        ac.created_at,
                        cf.feedback
                    FROM agent_comments ac
                    LEFT JOIN comment_feedback cf ON cf.comment_id = ac.comment_id
                    WHERE ac.workspace = ? AND ac.repo_slug = ?
                      AND ac.file_path NOT IN ('', '__summary__')
                      AND ac.line_number > 0
                      AND ac.created_at >= ?
                )
                SELECT
                    DATE_TRUNC('week', f.created_at)                                  AS week,
                    COUNT(*)                                                           AS total,
                    SUM(CASE WHEN f.resolved = true  AND f.feedback IS NULL THEN 1 ELSE 0 END) AS accepted,
                    SUM(CASE WHEN f.feedback = 'false_positive'               THEN 1 ELSE 0 END) AS rejected,
                    SUM(CASE WHEN f.resolved = false AND f.feedback IS NULL   THEN 1 ELSE 0 END) AS ignored
                FROM findings f
                GROUP BY week
                ORDER BY week
                """;

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            Timestamp sinceTs = Timestamp.from(since);
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, workspace);
            ps.setString(4, repoSlug);
            ps.setString(5, workspace);
            ps.setString(6, repoSlug);
            ps.setTimestamp(7, sinceTs);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Timestamp week = rs.getTimestamp("week");
                    row.put("week",           week != null ? week.toInstant().toString() : null);
                    long total    = rs.getLong("total");
                    long accepted = rs.getLong("accepted");
                    long rejected = rs.getLong("rejected");
                    long ignored  = rs.getLong("ignored");
                    row.put("total",          total);
                    row.put("accepted",       accepted);
                    row.put("rejected",       rejected);
                    row.put("ignored",        ignored);
                    row.put("acceptanceRate", total > 0 ? Math.round((double) accepted / total * 10000.0) / 10000.0 : 0.0);
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("AiAcceptanceStore.getTrend failed for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private Map<String, Object> getTotals(String workspace, String repoSlug, Instant since) {
        String sql = """
                SELECT
                    COUNT(*)                                                                    AS total,
                    SUM(CASE WHEN ac.resolved = true  AND cf.id IS NULL THEN 1 ELSE 0 END)     AS accepted,
                    SUM(CASE WHEN cf.feedback = 'false_positive'         THEN 1 ELSE 0 END)    AS rejected,
                    SUM(CASE WHEN ac.resolved = false AND cf.id IS NULL  THEN 1 ELSE 0 END)    AS ignored
                FROM agent_comments ac
                LEFT JOIN comment_feedback cf ON cf.comment_id = ac.comment_id
                WHERE ac.workspace = ? AND ac.repo_slug = ?
                  AND ac.file_path NOT IN ('', '__summary__')
                  AND ac.line_number > 0
                  AND ac.created_at >= ?
                """;

        Map<String, Object> totals = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setTimestamp(3, Timestamp.from(since));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long total    = rs.getLong("total");
                    long accepted = rs.getLong("accepted");
                    long rejected = rs.getLong("rejected");
                    long ignored  = rs.getLong("ignored");
                    totals.put("total",          total);
                    totals.put("accepted",       accepted);
                    totals.put("rejected",       rejected);
                    totals.put("ignored",        ignored);
                    totals.put("acceptanceRate", total > 0 ? Math.round((double) accepted / total * 10000.0) / 10000.0 : 0.0);
                    totals.put("rejectionRate",  total > 0 ? Math.round((double) rejected / total * 10000.0) / 10000.0 : 0.0);
                    totals.put("ignoredRate",    total > 0 ? Math.round((double) ignored  / total * 10000.0) / 10000.0 : 0.0);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("AiAcceptanceStore.getTotals failed for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return totals;
    }

    private List<Map<String, Object>> getBreakdown(String workspace, String repoSlug, Instant since, String groupBy) {
        String groupCol = switch (groupBy.toLowerCase()) {
            case "jobtype" -> "j.job_type";
            case "author"  -> "j.pr_author";
            default        -> "ac.repo_slug";
        };

        String sql = """
                WITH all_jobs AS (
                    SELECT job_id, job_type, pr_author
                    FROM jobs
                    WHERE workspace = ? AND repo_slug = ?
                    UNION ALL
                    SELECT job_id, job_type, pr_author
                    FROM job_history
                    WHERE workspace = ? AND repo_slug = ?
                )
                SELECT
                    %s                                                                          AS group_key,
                    COUNT(*)                                                                    AS total,
                    SUM(CASE WHEN ac.resolved = true  AND cf.id IS NULL THEN 1 ELSE 0 END)     AS accepted,
                    SUM(CASE WHEN cf.feedback = 'false_positive'         THEN 1 ELSE 0 END)    AS rejected,
                    SUM(CASE WHEN ac.resolved = false AND cf.id IS NULL  THEN 1 ELSE 0 END)    AS ignored
                FROM agent_comments ac
                LEFT JOIN comment_feedback cf ON cf.comment_id = ac.comment_id
                LEFT JOIN all_jobs j ON j.job_id = ac.review_job_id
                WHERE ac.workspace = ? AND ac.repo_slug = ?
                  AND ac.file_path NOT IN ('', '__summary__')
                  AND ac.line_number > 0
                  AND ac.created_at >= ?
                GROUP BY %s
                ORDER BY total DESC
                """.formatted(groupCol, groupCol);

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            Timestamp sinceTs = Timestamp.from(since);
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, workspace);
            ps.setString(4, repoSlug);
            ps.setString(5, workspace);
            ps.setString(6, repoSlug);
            ps.setTimestamp(7, sinceTs);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    long total    = rs.getLong("total");
                    long accepted = rs.getLong("accepted");
                    long rejected = rs.getLong("rejected");
                    long ignored  = rs.getLong("ignored");
                    row.put("groupKey",       rs.getString("group_key"));
                    row.put("total",          total);
                    row.put("accepted",       accepted);
                    row.put("rejected",       rejected);
                    row.put("ignored",        ignored);
                    row.put("acceptanceRate", total > 0 ? Math.round((double) accepted / total * 10000.0) / 10000.0 : 0.0);
                    row.put("rejectionRate",  total > 0 ? Math.round((double) rejected / total * 10000.0) / 10000.0 : 0.0);
                    row.put("ignoredRate",    total > 0 ? Math.round((double) ignored  / total * 10000.0) / 10000.0 : 0.0);
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("AiAcceptanceStore.getBreakdown failed for %s/%s groupBy=%s: %s",
                    workspace, repoSlug, groupBy, e.getMessage());
        }
        return results;
    }
}
