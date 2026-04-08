package com.eneve.agent.loganalysis;

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
 * PostgreSQL-backed store for {@code log_analysis_findings}.
 *
 * <p>One row per {@code (fingerprint, customer_id, environment_name)} — serves as both
 * the dedup/suppress cache and the findings surface for the UI.
 */
@ApplicationScoped
public class LogAnalysisFindingsStore {

    private static final Logger LOG = Logger.getLogger(LogAnalysisFindingsStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ── Dedup / suppress ──────────────────────────────────────────────────────

    private static final String SELECT_ALL_COLUMNS = """
            SELECT id, fingerprint, customer_id, environment_name, log_group_name,
                   exception_class, top_frames, sample_message,
                   first_seen_at, last_seen_at, occurrence_count, suppress_until,
                   ai_decision, severity, ai_reason, status, deep_analysis, analysed_at, jira_key,
                   monitoring_since, job_id, pr_url
            FROM log_analysis_findings
            """;

    /**
     * Returns the current finding for the given fingerprint+env, if one exists.
     * Used by Gate 1: if the row exists and {@code suppress_until > now()}, skip AI triage.
     */
    public Optional<LogAnalysisFinding> find(String fingerprint, String customerId, String environmentName) {
        String sql = SELECT_ALL_COLUMNS + "WHERE fingerprint = ? AND customer_id = ? AND environment_name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            ps.setString(2, customerId);
            ps.setString(3, environmentName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find finding %s/%s/%s: %s", fingerprint, customerId, environmentName, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Increments the occurrence count and updates last_seen_at for an already-suppressed finding.
     * Called by Gate 1 when suppress_until > now().
     */
    public void incrementOccurrence(long id, String sampleMessage) {
        String sql = """
                UPDATE log_analysis_findings
                SET occurrence_count = occurrence_count + 1,
                    last_seen_at     = now(),
                    sample_message   = COALESCE(?, sample_message)
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sampleMessage);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to increment occurrence for finding %d: %s", id, e.getMessage());
        }
    }

    /**
     * Inserts or updates a finding after AI triage. Sets ai_decision, severity, ai_reason,
     * and suppress_until = now() + 24h. On conflict (re-triage of existing row), updates in place.
     */
    public void upsertAfterTriage(String fingerprint, String customerId, String environmentName,
                                   String logGroupName, String exceptionClass, String topFrames,
                                   String sampleMessage, int occurrenceCount,
                                   String aiDecision, String severity, String aiReason) {
        String sql = """
                INSERT INTO log_analysis_findings
                    (fingerprint, customer_id, environment_name, log_group_name,
                     exception_class, top_frames, sample_message,
                     first_seen_at, last_seen_at, occurrence_count,
                     suppress_until, ai_decision, severity, ai_reason, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), now(), ?,
                        now() + interval '24 hours', ?, ?, ?, 'OPEN')
                ON CONFLICT (fingerprint, customer_id, environment_name) DO UPDATE
                    SET last_seen_at     = now(),
                        occurrence_count = log_analysis_findings.occurrence_count + EXCLUDED.occurrence_count,
                        sample_message   = EXCLUDED.sample_message,
                        suppress_until   = now() + interval '24 hours',
                        ai_decision      = EXCLUDED.ai_decision,
                        severity         = EXCLUDED.severity,
                        ai_reason        = EXCLUDED.ai_reason
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fingerprint);
            ps.setString(2, customerId);
            ps.setString(3, environmentName);
            ps.setString(4, logGroupName);
            ps.setString(5, exceptionClass);
            ps.setString(6, topFrames);
            ps.setString(7, sampleMessage);
            ps.setInt(8, occurrenceCount);
            ps.setString(9, aiDecision);
            ps.setString(10, severity);
            ps.setString(11, aiReason);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert finding %s/%s/%s: %s", fingerprint, customerId, environmentName, e.getMessage());
        }
    }

    // ── UI queries ────────────────────────────────────────────────────────────

    /**
     * Lists GENUINE findings that are OPEN or MONITORING for the UI, ordered by first_seen_at descending.
     * Optionally filtered by customerId and/or severity.
     */
    public List<LogAnalysisFinding> listGenuineFindings(String customerId, String severity, int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT_ALL_COLUMNS + "WHERE ai_decision = 'GENUINE' AND status IN ('OPEN','MONITORING')\n");
        List<Object> params = new ArrayList<>();

        if (customerId != null && !customerId.isBlank()) {
            sql.append(" AND customer_id = ?");
            params.add(customerId);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = ?");
            params.add(severity);
        }
        sql.append(" ORDER BY first_seen_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<LogAnalysisFinding> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list genuine findings: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Returns aggregate stats for the findings screen stat cards.
     */
    public FindingStats getStats() {
        String sql = """
                SELECT
                    COUNT(*) FILTER (WHERE ai_decision = 'GENUINE' AND status = 'OPEN')                                          AS open_total,
                    COUNT(*) FILTER (WHERE ai_decision = 'GENUINE' AND status = 'OPEN' AND severity = 'high')                    AS open_high,
                    COUNT(*) FILTER (WHERE ai_decision = 'GENUINE' AND status = 'OPEN' AND first_seen_at >= now() - interval '24 hours') AS new_today,
                    COUNT(*) FILTER (WHERE status = 'DISMISSED' AND last_seen_at >= now() - interval '7 days')                   AS dismissed_this_week,
                    COUNT(*) FILTER (WHERE status = 'MONITORING')                                                                AS monitoring_total
                FROM log_analysis_findings
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new FindingStats(
                        rs.getInt("open_total"),
                        rs.getInt("open_high"),
                        rs.getInt("new_today"),
                        rs.getInt("dismissed_this_week"),
                        rs.getInt("monitoring_total")
                );
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to get finding stats: %s", e.getMessage());
        }
        return new FindingStats(0, 0, 0, 0, 0);
    }

    /**
     * Lists FALSE_POSITIVE findings for the UI, ordered by last_seen_at descending.
     * Optionally filtered by customerId and/or severity.
     */
    public List<LogAnalysisFinding> listFalsePositiveFindings(String customerId, String severity, int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT_ALL_COLUMNS + "WHERE ai_decision = 'FALSE_POSITIVE'\n");
        List<Object> params = new ArrayList<>();

        if (customerId != null && !customerId.isBlank()) {
            sql.append(" AND customer_id = ?");
            params.add(customerId);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = ?");
            params.add(severity);
        }
        sql.append(" ORDER BY last_seen_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<LogAnalysisFinding> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list false positive findings: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Fetches a single finding by primary key.
     */
    public Optional<LogAnalysisFinding> findById(long id) {
        String sql = SELECT_ALL_COLUMNS + "WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find finding by id %d: %s", id, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Persists the Jira ticket key linked to a finding.
     *
     * @return true if the row was found and updated
     */
    public boolean saveJiraKey(long id, String jiraKey) {
        String sql = "UPDATE log_analysis_findings SET jira_key = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jiraKey);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to save jira key for finding %d: %s", id, e.getMessage());
            return false;
        }
    }

    /**
     * Persists the fix job ID and (optionally) the PR URL linked to a finding.
     *
     * @return true if the row was found and updated
     */
    public boolean saveJobAndPr(long id, String jobId, String prUrl) {
        String sql = "UPDATE log_analysis_findings SET job_id = ?, pr_url = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, prUrl);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to save job/pr for finding %d: %s", id, e.getMessage());
            return false;
        }
    }

    /**
     * Updates the PR URL for a finding (called once the fix job produces a PR).
     *
     * @return true if the row was found and updated
     */
    public boolean savePrUrl(long id, String prUrl) {
        String sql = "UPDATE log_analysis_findings SET pr_url = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prUrl);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to save pr_url for finding %d: %s", id, e.getMessage());
            return false;
        }
    }

    /**
     * Persists the deep-analysis text for a finding and marks it as analysed.
     *
     * @return true if the row was found and updated
     */
    public boolean saveDeepAnalysis(long id, String deepAnalysis) {
        String sql = """
                UPDATE log_analysis_findings
                SET deep_analysis = ?,
                    analysed_at   = now()
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deepAnalysis);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to save deep analysis for finding %d: %s", id, e.getMessage());
            return false;
        }
    }

    /**
     * Marks a finding as DISMISSED.
     *
     * @return true if the row was found and updated
     */
    public boolean dismiss(long id) {
        String sql = "UPDATE log_analysis_findings SET status = 'DISMISSED' WHERE id = ? AND status = 'OPEN'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to dismiss finding %d: %s", id, e.getMessage());
            return false;
        }
    }

    /**
     * Transitions a finding to MONITORING status (fix merged, watching for recurrence).
     * Only transitions from OPEN; idempotent if already MONITORING.
     *
     * @return true if the row was updated
     */
    public boolean setMonitoring(long id) {
        String sql = """
                UPDATE log_analysis_findings
                SET status = 'MONITORING', monitoring_since = now()
                WHERE id = ? AND status IN ('OPEN','MONITORING')
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to set finding %d to MONITORING: %s", id, e.getMessage());
            return false;
        }
    }

    /**
     * Looks up a finding by its linked Jira key.
     */
    public Optional<LogAnalysisFinding> findByJiraKey(String jiraKey) {
        String sql = SELECT_ALL_COLUMNS + "WHERE jira_key = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jiraKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find finding by jira key %s: %s", jiraKey, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Closes all MONITORING findings whose {@code monitoring_since} is older than {@code days} days.
     *
     * @return number of rows closed
     */
    public int closeExpiredMonitoring(int days) {
        String sql = """
                UPDATE log_analysis_findings
                SET status = 'CLOSED'
                WHERE status = 'MONITORING'
                  AND monitoring_since < now() - (? || ' days')::interval
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, days);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                LOG.infof("LogAnalysisFindingsStore: closed %d findings after %d-day monitoring window", updated, days);
            }
            return updated;
        } catch (SQLException e) {
            LOG.errorf("Failed to close expired monitoring findings: %s", e.getMessage());
            return 0;
        }
    }

    /**
     * Lists the top open/high-severity findings for the dashboard attention section.
     * Returns up to {@code limit} GENUINE findings with status OPEN, ordered by severity then first_seen_at.
     */
    public List<LogAnalysisFinding> listAttentionFindings(int limit) {
        String sql = SELECT_ALL_COLUMNS + """
                WHERE ai_decision = 'GENUINE' AND status = 'OPEN'
                ORDER BY
                    CASE severity WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
                    first_seen_at ASC
                LIMIT ?
                """;
        List<LogAnalysisFinding> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(map(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list attention findings: %s", e.getMessage());
        }
        return results;
    }

    // ── Retention ─────────────────────────────────────────────────────────────

    /**
     * Deletes rows where {@code last_seen_at} is older than {@code days} days.
     *
     * @return number of rows deleted
     */
    public int pruneOlderThan(int days) {
        String sql = "DELETE FROM log_analysis_findings WHERE last_seen_at < now() - (? || ' days')::interval";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, days);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                LOG.infof("LogAnalysisFindingsStore: pruned %d findings older than %d days", deleted, days);
            }
            return deleted;
        } catch (SQLException e) {
            LOG.errorf("Failed to prune old findings: %s", e.getMessage());
            return 0;
        }
    }

    // ── Row mapper ────────────────────────────────────────────────────────────

    private LogAnalysisFinding map(ResultSet rs) throws SQLException {
        return new LogAnalysisFinding(
                rs.getLong("id"),
                rs.getString("fingerprint"),
                rs.getString("customer_id"),
                rs.getString("environment_name"),
                rs.getString("log_group_name"),
                rs.getString("exception_class"),
                rs.getString("top_frames"),
                rs.getString("sample_message"),
                toInstant(rs.getTimestamp("first_seen_at")),
                toInstant(rs.getTimestamp("last_seen_at")),
                rs.getInt("occurrence_count"),
                toInstant(rs.getTimestamp("suppress_until")),
                rs.getString("ai_decision"),
                rs.getString("severity"),
                rs.getString("ai_reason"),
                rs.getString("status"),
                rs.getString("deep_analysis"),
                toInstant(rs.getTimestamp("analysed_at")),
                rs.getString("jira_key"),
                toInstant(rs.getTimestamp("monitoring_since")),
                rs.getString("job_id"),
                rs.getString("pr_url")
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    // ── Stats DTO ─────────────────────────────────────────────────────────────

    public record FindingStats(
            int openTotal,
            int openHigh,
            int newToday,
            int dismissedThisWeek,
            int monitoringTotal
    ) {}
}
