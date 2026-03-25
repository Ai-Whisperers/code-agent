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
 * Aggregation queries that power the per-developer review scorecard.
 * Joins job data (who opened the PR) with agent_comments (findings) to produce
 * per-author stats within a configurable rolling time window.
 */
@ApplicationScoped
public class DeveloperMetricsStore {

    private static final Logger LOG = Logger.getLogger(DeveloperMetricsStore.class);

    @Inject
    AgroalDataSource dataSource;

    /**
     * Returns per-author review quality stats for a repo within the last {@code days} days.
     *
     * <p>Each entry contains:
     * <ul>
     *   <li>{@code author}            – PR author string</li>
     *   <li>{@code totalPrs}          – distinct PRs reviewed</li>
     *   <li>{@code totalFindings}     – total inline findings posted</li>
     *   <li>{@code resolvedFindings}  – findings resolved by the developer</li>
     *   <li>{@code resolutionRate}    – resolvedFindings / totalFindings (0 when no findings)</li>
     *   <li>{@code lastPrAt}          – ISO-8601 timestamp of the most recent review job</li>
     * </ul>
     */
    public List<Map<String, Object>> scorecardByAuthor(String workspace, String repoSlug, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        String sql = """
                WITH review_jobs AS (
                    SELECT pr_id, pr_author, created_at
                    FROM jobs
                    WHERE workspace = ? AND repo_slug = ?
                      AND job_type = 'REVIEW'
                      AND pr_author IS NOT NULL AND pr_author <> ''
                      AND created_at >= ?
                    UNION ALL
                    SELECT pr_id, pr_author, created_at
                    FROM job_history
                    WHERE workspace = ? AND repo_slug = ?
                      AND job_type = 'REVIEW'
                      AND pr_author IS NOT NULL AND pr_author <> ''
                      AND created_at >= ?
                ),
                findings AS (
                    SELECT
                        pr_id,
                        COUNT(*)                                             AS total_findings,
                        SUM(CASE WHEN resolved = true THEN 1 ELSE 0 END)    AS resolved_findings
                    FROM agent_comments
                    WHERE workspace = ? AND repo_slug = ?
                      AND file_path NOT IN ('', '__summary__')
                      AND line_number > 0
                    GROUP BY pr_id
                )
                SELECT
                    j.pr_author                                                         AS author,
                    COUNT(DISTINCT j.pr_id)                                             AS total_prs,
                    COALESCE(SUM(f.total_findings),    0)                               AS total_findings,
                    COALESCE(SUM(f.resolved_findings), 0)                               AS resolved_findings,
                    ROUND(
                        COALESCE(SUM(f.resolved_findings), 0)::numeric
                        / NULLIF(SUM(f.total_findings), 0), 4
                    )                                                                   AS resolution_rate,
                    MAX(j.created_at)                                                   AS last_pr_at
                FROM review_jobs j
                LEFT JOIN findings f ON f.pr_id = j.pr_id
                GROUP BY j.pr_author
                ORDER BY total_findings DESC, total_prs DESC
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
                    row.put("author",           rs.getString("author"));
                    row.put("totalPrs",         rs.getLong("total_prs"));
                    row.put("totalFindings",    rs.getLong("total_findings"));
                    row.put("resolvedFindings", rs.getLong("resolved_findings"));
                    double rate = rs.getDouble("resolution_rate");
                    row.put("resolutionRate", rs.wasNull() ? 0.0 : rate);
                    Timestamp lastPr = rs.getTimestamp("last_pr_at");
                    row.put("lastPrAt", lastPr != null ? lastPr.toInstant().toString() : null);
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query developer scorecard for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return results;
    }
}
