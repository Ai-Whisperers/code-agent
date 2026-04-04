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
 * Aggregation queries for PR cycle time metrics.
 *
 * <p>Joins {@code open_pull_requests} (where {@code created_on}/{@code updated_on} are stored as
 * TEXT ISO-8601 strings from Bitbucket) with {@code jobs}/{@code job_history} REVIEW entries to
 * compute time from PR open to first agent review and from PR open to merge.
 *
 * <p>Only PRs that have at least one REVIEW job are included ({@code pr_id IS NOT NULL} guard).
 * Rows where the TEXT timestamp cannot be cast to TIMESTAMPTZ are skipped via {@code TRY_CAST}
 * emulated with a CASE/regexp guard.
 */
@ApplicationScoped
public class PrCycleTimeStore {

    private static final Logger LOG = Logger.getLogger(PrCycleTimeStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ─── Summary ─────────────────────────────────────────────────────────────

    /**
     * Returns avg/p50/p95 hours for open→agent-review and open→merge,
     * grouped by {@code repo_slug} or {@code author} depending on {@code groupBy}.
     *
     * @param workspace workspace slug
     * @param repoSlug  repository slug (pass {@code null} to query all repos in the workspace)
     * @param days      rolling window in days (1–365)
     * @param groupBy   {@code "repo"} or {@code "author"}
     */
    public List<Map<String, Object>> getSummary(String workspace, String repoSlug, int days, String groupBy) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        boolean byAuthor = "author".equalsIgnoreCase(groupBy);

        // Group column must reference aliases present in the final SELECT scope (fr or pd).
        // fr carries pr_author and repo_slug from the all_review_jobs CTE.
        String groupCol = byAuthor ? "fr.pr_author" : "fr.repo_slug";

        String repoFilter = repoSlug != null ? "AND repo_slug = ?" : "";

        String sql = """
                WITH all_review_jobs AS (
                    SELECT pr_id, pr_author, repo_slug, created_at
                    FROM jobs
                    WHERE workspace = ?
                      AND job_type = 'REVIEW'
                      AND pr_id IS NOT NULL AND pr_id <> ''
                      AND created_at >= ?
                      %s
                    UNION ALL
                    SELECT pr_id, pr_author, repo_slug, created_at
                    FROM job_history
                    WHERE workspace = ?
                      AND job_type = 'REVIEW'
                      AND pr_id IS NOT NULL AND pr_id <> ''
                      AND created_at >= ?
                      %s
                ),
                first_reviews AS (
                    SELECT pr_id, pr_author, repo_slug, MIN(created_at) AS first_review_at
                    FROM all_review_jobs
                    GROUP BY pr_id, pr_author, repo_slug
                ),
                pr_data AS (
                    SELECT
                        pr.pr_id,
                        pr.repo_slug,
                        CASE
                            WHEN pr.created_on ~ '^\\d{4}-\\d{2}-\\d{2}'
                            THEN pr.created_on::TIMESTAMPTZ
                            ELSE NULL
                        END AS opened_at,
                        CASE
                            WHEN pr.status = 'MERGED' AND pr.updated_on ~ '^\\d{4}-\\d{2}-\\d{2}'
                            THEN pr.updated_on::TIMESTAMPTZ
                            ELSE NULL
                        END AS merged_at
                    FROM open_pull_requests pr
                    WHERE pr.workspace = ?
                      %s
                )
                SELECT
                    %s                                                                AS group_key,
                    COUNT(DISTINCT fr.pr_id)                                         AS total_prs,
                    ROUND(AVG(
                        EXTRACT(EPOCH FROM (fr.first_review_at - pd.opened_at)) / 3600.0
                    )::numeric, 2)                                                   AS avg_open_to_review_hrs,
                    ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY
                        EXTRACT(EPOCH FROM (fr.first_review_at - pd.opened_at)) / 3600.0
                    )::numeric, 2)                                                   AS p50_open_to_review_hrs,
                    ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY
                        EXTRACT(EPOCH FROM (fr.first_review_at - pd.opened_at)) / 3600.0
                    )::numeric, 2)                                                   AS p95_open_to_review_hrs,
                    ROUND(AVG(
                        EXTRACT(EPOCH FROM (pd.merged_at - pd.opened_at)) / 3600.0
                    )::numeric, 2)                                                   AS avg_open_to_merge_hrs,
                    ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY
                        EXTRACT(EPOCH FROM (pd.merged_at - pd.opened_at)) / 3600.0
                    )::numeric, 2)                                                   AS p50_open_to_merge_hrs,
                    ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY
                        EXTRACT(EPOCH FROM (pd.merged_at - pd.opened_at)) / 3600.0
                    )::numeric, 2)                                                   AS p95_open_to_merge_hrs
                FROM first_reviews fr
                JOIN pr_data pd ON pd.pr_id = fr.pr_id AND pd.repo_slug = fr.repo_slug
                WHERE pd.opened_at IS NOT NULL
                GROUP BY %s
                ORDER BY total_prs DESC
                """.formatted(repoFilter, repoFilter,
                repoSlug != null ? "AND pr.repo_slug = ?" : "",
                groupCol, groupCol);

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            Timestamp sinceTs = Timestamp.from(since);
            int idx = 1;
            ps.setString(idx++, workspace);
            ps.setTimestamp(idx++, sinceTs);
            if (repoSlug != null) ps.setString(idx++, repoSlug);
            ps.setString(idx++, workspace);
            ps.setTimestamp(idx++, sinceTs);
            if (repoSlug != null) ps.setString(idx++, repoSlug);
            ps.setString(idx++, workspace);
            if (repoSlug != null) ps.setString(idx++, repoSlug);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("groupKey",             rs.getString("group_key"));
                    row.put("totalPrs",             rs.getLong("total_prs"));
                    row.put("avgOpenToReviewHrs",   nullableDouble(rs, "avg_open_to_review_hrs"));
                    row.put("p50OpenToReviewHrs",   nullableDouble(rs, "p50_open_to_review_hrs"));
                    row.put("p95OpenToReviewHrs",   nullableDouble(rs, "p95_open_to_review_hrs"));
                    row.put("avgOpenToMergeHrs",    nullableDouble(rs, "avg_open_to_merge_hrs"));
                    row.put("p50OpenToMergeHrs",    nullableDouble(rs, "p50_open_to_merge_hrs"));
                    row.put("p95OpenToMergeHrs",    nullableDouble(rs, "p95_open_to_merge_hrs"));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("PrCycleTimeStore.getSummary failed for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    // ─── Trend ───────────────────────────────────────────────────────────────

    /**
     * Returns weekly-bucketed avg open→agent-review hours for the given repo,
     * suitable for a time-series line chart.
     *
     * @param workspace workspace slug
     * @param repoSlug  repository slug
     * @param days      rolling window in days (1–365)
     */
    public List<Map<String, Object>> getTrend(String workspace, String repoSlug, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        String sql = """
                WITH all_review_jobs AS (
                    SELECT pr_id, repo_slug, created_at
                    FROM jobs
                    WHERE workspace = ? AND repo_slug = ?
                      AND job_type = 'REVIEW'
                      AND pr_id IS NOT NULL AND pr_id <> ''
                      AND created_at >= ?
                    UNION ALL
                    SELECT pr_id, repo_slug, created_at
                    FROM job_history
                    WHERE workspace = ? AND repo_slug = ?
                      AND job_type = 'REVIEW'
                      AND pr_id IS NOT NULL AND pr_id <> ''
                      AND created_at >= ?
                ),
                first_reviews AS (
                    SELECT pr_id, repo_slug, MIN(created_at) AS first_review_at
                    FROM all_review_jobs
                    GROUP BY pr_id, repo_slug
                ),
                pr_data AS (
                    SELECT
                        pr.pr_id,
                        pr.repo_slug,
                        CASE
                            WHEN pr.created_on ~ '^\\d{4}-\\d{2}-\\d{2}'
                            THEN pr.created_on::TIMESTAMPTZ
                            ELSE NULL
                        END AS opened_at
                    FROM open_pull_requests pr
                    WHERE pr.workspace = ? AND pr.repo_slug = ?
                )
                SELECT
                    DATE_TRUNC('week', fr.first_review_at)                           AS week,
                    ROUND(AVG(
                        EXTRACT(EPOCH FROM (fr.first_review_at - pd.opened_at)) / 3600.0
                    )::numeric, 2)                                                   AS avg_open_to_review_hrs,
                    COUNT(*)                                                         AS pr_count
                FROM first_reviews fr
                JOIN pr_data pd ON pd.pr_id = fr.pr_id AND pd.repo_slug = fr.repo_slug
                WHERE pd.opened_at IS NOT NULL
                GROUP BY week
                ORDER BY week
                """;

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            Timestamp sinceTs = Timestamp.from(since);
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setTimestamp(3, sinceTs);
            ps.setString(4, workspace);
            ps.setString(5, repoSlug);
            ps.setTimestamp(6, sinceTs);
            ps.setString(7, workspace);
            ps.setString(8, repoSlug);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Timestamp week = rs.getTimestamp("week");
                    row.put("week",               week != null ? week.toInstant().toString() : null);
                    row.put("avgOpenToReviewHrs", nullableDouble(rs, "avg_open_to_review_hrs"));
                    row.put("prCount",            rs.getLong("pr_count"));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("PrCycleTimeStore.getTrend failed for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        double val = rs.getDouble(col);
        return rs.wasNull() ? null : val;
    }
}
