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

    /**
     * Returns the current finding for the given fingerprint+env, if one exists.
     * Used by Gate 1: if the row exists and {@code suppress_until > now()}, skip AI triage.
     */
    public Optional<LogAnalysisFinding> find(String fingerprint, String customerId, String environmentName) {
        String sql = """
                SELECT id, fingerprint, customer_id, environment_name, log_group_name,
                       exception_class, top_frames, sample_message,
                       first_seen_at, last_seen_at, occurrence_count, suppress_until,
                       ai_decision, severity, ai_reason, status
                FROM log_analysis_findings
                WHERE fingerprint = ? AND customer_id = ? AND environment_name = ?
                """;
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
     * Lists GENUINE, OPEN findings for the UI, ordered by first_seen_at descending.
     * Optionally filtered by customerId and/or severity.
     */
    public List<LogAnalysisFinding> listGenuineFindings(String customerId, String severity, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, fingerprint, customer_id, environment_name, log_group_name,
                       exception_class, top_frames, sample_message,
                       first_seen_at, last_seen_at, occurrence_count, suppress_until,
                       ai_decision, severity, ai_reason, status
                FROM log_analysis_findings
                WHERE ai_decision = 'GENUINE' AND status = 'OPEN'
                """);
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
                    COUNT(*) FILTER (WHERE status = 'DISMISSED' AND last_seen_at >= now() - interval '7 days')                   AS dismissed_this_week
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
                        rs.getInt("dismissed_this_week")
                );
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to get finding stats: %s", e.getMessage());
        }
        return new FindingStats(0, 0, 0, 0);
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
                rs.getString("status")
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
            int dismissedThisWeek
    ) {}
}
