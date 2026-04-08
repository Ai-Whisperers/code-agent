package com.eneve.agent.knowledge;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed store for Knowledge Graph snapshots, per-file scores, and bus-factor flags.
 */
@ApplicationScoped
public class KnowledgeGraphStore {

    private static final Logger LOG = Logger.getLogger(KnowledgeGraphStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ── Snapshots ─────────────────────────────────────────────────────────────

    /**
     * Creates a new snapshot row and returns its generated id.
     */
    public long createSnapshot(String productId, int lookbackDays) {
        String sql = """
                INSERT INTO knowledge_snapshots (product_id, lookback_days)
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
            LOG.errorf("Failed to create knowledge snapshot: %s", e.getMessage());
        }
        return -1;
    }

    /** Updates the summary counters on a snapshot after all scores have been written. */
    public void updateSnapshotStats(long snapshotId, int totalRepos, int totalAuthors, int totalFiles) {
        String sql = """
                UPDATE knowledge_snapshots
                SET total_repos = ?, total_authors = ?, total_files = ?
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, totalRepos);
            ps.setInt(2, totalAuthors);
            ps.setInt(3, totalFiles);
            ps.setLong(4, snapshotId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to update snapshot stats for id=%d: %s", snapshotId, e.getMessage());
        }
    }

    /** Returns all snapshots, newest first. */
    public List<KnowledgeSnapshot> listSnapshots() {
        String sql = """
                SELECT id, product_id, computed_at, lookback_days,
                       total_repos, total_authors, total_files
                FROM knowledge_snapshots
                ORDER BY computed_at DESC
                """;
        List<KnowledgeSnapshot> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) results.add(mapSnapshot(rs));
        } catch (SQLException e) {
            LOG.errorf("Failed to list knowledge snapshots: %s", e.getMessage());
        }
        return results;
    }

    /** Returns the most recent snapshot. */
    public Optional<KnowledgeSnapshot> findLatestSnapshot() {
        String sql = """
                SELECT id, product_id, computed_at, lookback_days,
                       total_repos, total_authors, total_files
                FROM knowledge_snapshots
                ORDER BY computed_at DESC
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return Optional.of(mapSnapshot(rs));
        } catch (SQLException e) {
            LOG.errorf("Failed to find latest knowledge snapshot: %s", e.getMessage());
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
                DELETE FROM knowledge_snapshots
                WHERE computed_at < now() - (? || ' days')::INTERVAL
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, retentionDays);
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to delete old knowledge snapshots: %s", e.getMessage());
            return 0;
        }
    }

    // ── Scores ────────────────────────────────────────────────────────────────

    /**
     * Batch-inserts a list of file-level scores for a snapshot.
     * Service scores are updated in a second pass after all rows are inserted.
     */
    public void insertScores(long snapshotId, List<KnowledgeScore> scores) {
        String sql = """
                INSERT INTO knowledge_scores
                    (snapshot_id, author_email, author_name, repo_slug, file_path,
                     commit_count, lines_added, lines_deleted, blame_lines, total_lines,
                     last_commit_at, score, service_score)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (KnowledgeScore s : scores) {
                ps.setLong(1, snapshotId);
                ps.setString(2, s.authorEmail());
                ps.setString(3, s.authorName());
                ps.setString(4, s.repoSlug());
                ps.setString(5, s.filePath());
                ps.setInt(6, s.commitCount());
                ps.setInt(7, s.linesAdded());
                ps.setInt(8, s.linesDeleted());
                ps.setInt(9, s.blameLines());
                ps.setInt(10, s.totalLines());
                if (s.lastCommitAt() != null) {
                    ps.setDate(11, Date.valueOf(s.lastCommitAt()));
                } else {
                    ps.setNull(11, Types.DATE);
                }
                ps.setBigDecimal(12, s.score());
                ps.setBigDecimal(13, s.serviceScore());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOG.errorf("Failed to insert knowledge scores for snapshot %d: %s", snapshotId, e.getMessage());
        }
    }

    /** Returns file-level scores for a snapshot, optionally filtered by repo and/or author. */
    public List<KnowledgeScore> findScores(long snapshotId, String repoSlug, String authorEmail) {
        StringBuilder sql = new StringBuilder("""
                SELECT snapshot_id, author_email, author_name, repo_slug, file_path,
                       commit_count, lines_added, lines_deleted, blame_lines, total_lines,
                       last_commit_at, score, service_score
                FROM knowledge_scores
                WHERE snapshot_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(snapshotId);
        if (repoSlug != null && !repoSlug.isBlank()) {
            sql.append(" AND repo_slug = ?");
            params.add(repoSlug);
        }
        if (authorEmail != null && !authorEmail.isBlank()) {
            sql.append(" AND author_email = ?");
            params.add(authorEmail);
        }
        sql.append(" ORDER BY score DESC");

        List<KnowledgeScore> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapScore(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query knowledge scores: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Returns per-repo per-author service scores for a snapshot.
     * One row per (author_email, repo_slug) with the aggregated service_score.
     */
    public List<ServiceScore> findServiceScores(long snapshotId) {
        String sql = """
                SELECT author_email, author_name, repo_slug,
                       SUM(score) AS service_score
                FROM knowledge_scores
                WHERE snapshot_id = ?
                GROUP BY author_email, author_name, repo_slug
                ORDER BY service_score DESC
                """;
        List<ServiceScore> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new ServiceScore(
                            rs.getString("author_email"),
                            rs.getString("author_name"),
                            rs.getString("repo_slug"),
                            rs.getBigDecimal("service_score")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query service scores: %s", e.getMessage());
        }
        return results;
    }

    /** Returns distinct author emails + names for a snapshot. */
    public List<AuthorSummary> findAuthors(long snapshotId) {
        String sql = """
                SELECT DISTINCT author_email, author_name
                FROM knowledge_scores
                WHERE snapshot_id = ?
                ORDER BY author_email
                """;
        List<AuthorSummary> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new AuthorSummary(
                            rs.getString("author_email"),
                            rs.getString("author_name")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query authors: %s", e.getMessage());
        }
        return results;
    }

    // ── Bus factor ────────────────────────────────────────────────────────────

    /** Batch-inserts bus-factor rows for a snapshot. */
    public void insertBusFactorRows(long snapshotId, List<BusFactorRow> rows) {
        String sql = """
                INSERT INTO knowledge_bus_factor
                    (snapshot_id, repo_slug, file_path,
                     top_author_email, top_author_name, top_score, top_ownership_pct,
                     second_author_email, second_score, bus_factor_flag, risk_level)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (BusFactorRow r : rows) {
                ps.setLong(1, snapshotId);
                ps.setString(2, r.repoSlug());
                ps.setString(3, r.filePath());
                ps.setString(4, r.topAuthorEmail());
                ps.setString(5, r.topAuthorName());
                ps.setBigDecimal(6, r.topScore());
                ps.setBigDecimal(7, r.topOwnershipPct());
                ps.setString(8, r.secondAuthorEmail());
                ps.setBigDecimal(9, r.secondScore());
                ps.setBoolean(10, r.busFactorFlag());
                ps.setString(11, r.riskLevel());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOG.errorf("Failed to insert bus-factor rows for snapshot %d: %s", snapshotId, e.getMessage());
        }
    }

    /** Returns bus-factor rows for a snapshot, optionally filtered by repo. */
    public List<BusFactorRow> findBusFactor(long snapshotId, String repoSlug, boolean flaggedOnly) {
        StringBuilder sql = new StringBuilder("""
                SELECT snapshot_id, repo_slug, file_path,
                       top_author_email, top_author_name, top_score, top_ownership_pct,
                       second_author_email, second_score, bus_factor_flag, risk_level
                FROM knowledge_bus_factor
                WHERE snapshot_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(snapshotId);
        if (repoSlug != null && !repoSlug.isBlank()) {
            sql.append(" AND repo_slug = ?");
            params.add(repoSlug);
        }
        if (flaggedOnly) {
            sql.append(" AND bus_factor_flag = true");
        }
        sql.append(" ORDER BY top_ownership_pct DESC");

        List<BusFactorRow> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapBusFactor(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query bus-factor rows: %s", e.getMessage());
        }
        return results;
    }

    // ── Row mappers ───────────────────────────────────────────────────────────

    private KnowledgeSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("computed_at");
        return new KnowledgeSnapshot(
                rs.getLong("id"),
                rs.getString("product_id"),
                ts != null ? ts.toInstant() : null,
                rs.getInt("lookback_days"),
                rs.getInt("total_repos"),
                rs.getInt("total_authors"),
                rs.getInt("total_files")
        );
    }

    private KnowledgeScore mapScore(ResultSet rs) throws SQLException {
        Date d = rs.getDate("last_commit_at");
        return new KnowledgeScore(
                rs.getLong("snapshot_id"),
                rs.getString("author_email"),
                rs.getString("author_name"),
                rs.getString("repo_slug"),
                rs.getString("file_path"),
                rs.getInt("commit_count"),
                rs.getInt("lines_added"),
                rs.getInt("lines_deleted"),
                rs.getInt("blame_lines"),
                rs.getInt("total_lines"),
                d != null ? d.toLocalDate() : null,
                rs.getBigDecimal("score"),
                rs.getBigDecimal("service_score")
        );
    }

    private BusFactorRow mapBusFactor(ResultSet rs) throws SQLException {
        return new BusFactorRow(
                rs.getLong("snapshot_id"),
                rs.getString("repo_slug"),
                rs.getString("file_path"),
                rs.getString("top_author_email"),
                rs.getString("top_author_name"),
                rs.getBigDecimal("top_score"),
                rs.getBigDecimal("top_ownership_pct"),
                rs.getString("second_author_email"),
                rs.getBigDecimal("second_score"),
                rs.getBoolean("bus_factor_flag"),
                rs.getString("risk_level")
        );
    }

    // ── DTO records ───────────────────────────────────────────────────────────

    public record KnowledgeSnapshot(
            long id,
            String productId,
            Instant computedAt,
            int lookbackDays,
            int totalRepos,
            int totalAuthors,
            int totalFiles
    ) {}

    public record KnowledgeScore(
            long snapshotId,
            String authorEmail,
            String authorName,
            String repoSlug,
            String filePath,
            int commitCount,
            int linesAdded,
            int linesDeleted,
            int blameLines,
            int totalLines,
            LocalDate lastCommitAt,
            java.math.BigDecimal score,
            java.math.BigDecimal serviceScore
    ) {}

    public record ServiceScore(
            String authorEmail,
            String authorName,
            String repoSlug,
            java.math.BigDecimal serviceScore
    ) {}

    public record AuthorSummary(
            String authorEmail,
            String authorName
    ) {}

    public record BusFactorRow(
            long snapshotId,
            String repoSlug,
            String filePath,
            String topAuthorEmail,
            String topAuthorName,
            java.math.BigDecimal topScore,
            java.math.BigDecimal topOwnershipPct,
            String secondAuthorEmail,
            java.math.BigDecimal secondScore,
            boolean busFactorFlag,
            String riskLevel
    ) {}
}
