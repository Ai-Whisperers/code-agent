package com.eneve.agent.techdebt;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed store for Technical Debt Heatmap snapshots and per-file debt scores.
 */
@ApplicationScoped
public class TechDebtStore {

    private static final Logger LOG = Logger.getLogger(TechDebtStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ── Snapshots ─────────────────────────────────────────────────────────────

    /** Creates a new snapshot row and returns its generated id. */
    public long createSnapshot(String productId, int lookbackDays) {
        String sql = """
                INSERT INTO tech_debt_snapshots (product_id, lookback_days)
                VALUES (?, ?)
                RETURNING id
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productId);
            ps.setInt(2, lookbackDays);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to create tech-debt snapshot: %s", e.getMessage());
        }
        return -1;
    }

    /** Updates the total_files counter on a snapshot after all file rows have been written. */
    public void updateSnapshotStats(long snapshotId, int totalFiles) {
        String sql = "UPDATE tech_debt_snapshots SET total_files = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, totalFiles);
            ps.setLong(2, snapshotId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to update tech-debt snapshot stats for id=%d: %s", snapshotId, e.getMessage());
        }
    }

    /** Returns all snapshots, newest first. */
    public List<TechDebtSnapshot> listSnapshots() {
        String sql = """
                SELECT id, product_id, computed_at, lookback_days, total_files
                FROM tech_debt_snapshots
                ORDER BY computed_at DESC
                """;
        List<TechDebtSnapshot> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) results.add(mapSnapshot(rs));
        } catch (SQLException e) {
            LOG.errorf("Failed to list tech-debt snapshots: %s", e.getMessage());
        }
        return results;
    }

    /** Returns the most recent snapshot. */
    public Optional<TechDebtSnapshot> findLatestSnapshot() {
        String sql = """
                SELECT id, product_id, computed_at, lookback_days, total_files
                FROM tech_debt_snapshots
                ORDER BY computed_at DESC
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return Optional.of(mapSnapshot(rs));
        } catch (SQLException e) {
            LOG.errorf("Failed to find latest tech-debt snapshot: %s", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Deletes snapshots (and their cascading child rows) older than {@code retentionDays} days.
     *
     * @return number of snapshots deleted
     */
    public int deleteOldSnapshots(int retentionDays) {
        String sql = """
                DELETE FROM tech_debt_snapshots
                WHERE computed_at < now() - (? || ' days')::INTERVAL
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, retentionDays);
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to delete old tech-debt snapshots: %s", e.getMessage());
            return 0;
        }
    }

    // ── File rows ─────────────────────────────────────────────────────────────

    /** Batch-inserts a list of per-file debt rows for a snapshot. */
    public void insertFiles(long snapshotId, List<TechDebtFileRow> rows) {
        String sql = """
                INSERT INTO tech_debt_files
                    (snapshot_id, repo_slug, file_path,
                     complexity_score, coverage_gap, churn_score, staleness_score,
                     debt_score, last_commit_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (TechDebtFileRow r : rows) {
                ps.setLong(1, snapshotId);
                ps.setString(2, r.repoSlug());
                ps.setString(3, r.filePath());
                ps.setBigDecimal(4, r.complexityScore());
                ps.setBigDecimal(5, r.coverageGap());
                ps.setBigDecimal(6, r.churnScore());
                ps.setBigDecimal(7, r.stalenessScore());
                ps.setBigDecimal(8, r.debtScore());
                if (r.lastCommitAt() != null) {
                    ps.setDate(9, Date.valueOf(r.lastCommitAt()));
                } else {
                    ps.setNull(9, Types.DATE);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOG.errorf("Failed to insert tech-debt file rows for snapshot %d: %s", snapshotId, e.getMessage());
        }
    }

    /**
     * Returns file-level debt rows for a snapshot, optionally filtered by repo slug.
     * Results are ordered by {@code debt_score DESC}.
     */
    public List<TechDebtFileRow> findFiles(long snapshotId, String repoSlug) {
        StringBuilder sql = new StringBuilder("""
                SELECT snapshot_id, repo_slug, file_path,
                       complexity_score, coverage_gap, churn_score, staleness_score,
                       debt_score, last_commit_at
                FROM tech_debt_files
                WHERE snapshot_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(snapshotId);
        if (repoSlug != null && !repoSlug.isBlank()) {
            sql.append(" AND repo_slug = ?");
            params.add(repoSlug);
        }
        sql.append(" ORDER BY debt_score DESC");

        List<TechDebtFileRow> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapFileRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query tech-debt file rows: %s", e.getMessage());
        }
        return results;
    }

    // ── Row mappers ───────────────────────────────────────────────────────────

    private TechDebtSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("computed_at");
        return new TechDebtSnapshot(
                rs.getLong("id"),
                rs.getString("product_id"),
                ts != null ? ts.toInstant() : null,
                rs.getInt("lookback_days"),
                rs.getInt("total_files")
        );
    }

    private TechDebtFileRow mapFileRow(ResultSet rs) throws SQLException {
        Date d = rs.getDate("last_commit_at");
        return new TechDebtFileRow(
                rs.getLong("snapshot_id"),
                rs.getString("repo_slug"),
                rs.getString("file_path"),
                rs.getBigDecimal("complexity_score"),
                rs.getBigDecimal("coverage_gap"),
                rs.getBigDecimal("churn_score"),
                rs.getBigDecimal("staleness_score"),
                rs.getBigDecimal("debt_score"),
                d != null ? d.toLocalDate() : null
        );
    }

    // ── DTO records ───────────────────────────────────────────────────────────

    public record TechDebtSnapshot(
            long id,
            String productId,
            Instant computedAt,
            int lookbackDays,
            int totalFiles
    ) {}

    public record TechDebtFileRow(
            long snapshotId,
            String repoSlug,
            String filePath,
            BigDecimal complexityScore,
            BigDecimal coverageGap,
            BigDecimal churnScore,
            BigDecimal stalenessScore,
            BigDecimal debtScore,
            LocalDate lastCommitAt
    ) {}
}
