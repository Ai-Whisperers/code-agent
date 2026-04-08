package com.eneve.agent.agent.store;

import com.eneve.agent.agent.model.CommentFeedbackEntry;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL-backed store for developer feedback on review findings.
 * Powers false-positive rate metrics and auto-suppression of recurring noise.
 */
@ApplicationScoped
public class CommentFeedbackStore {

    private static final Logger LOG = Logger.getLogger(CommentFeedbackStore.class);

    @Inject
    AgroalDataSource dataSource;

    public void save(CommentFeedbackEntry entry) {
        String sql = """
                INSERT INTO comment_feedback
                    (comment_id, pr_id, workspace, repo_slug, feedback, category, pattern, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, entry.commentId());
            ps.setString(2, entry.prId());
            ps.setString(3, entry.workspace());
            ps.setString(4, entry.repoSlug());
            ps.setString(5, entry.feedback());
            setNullableString(ps, 6, entry.category());
            setNullableString(ps, 7, entry.pattern());
            ps.setString(8, entry.createdBy());
            ps.setTimestamp(9, Timestamp.from(entry.createdAt() != null ? entry.createdAt() : Instant.now()));
            ps.executeUpdate();
            LOG.debugf("Stored %s feedback for comment %d (%s/%s)",
                    entry.feedback(), entry.commentId(), entry.workspace(), entry.repoSlug());
        } catch (SQLException e) {
            LOG.errorf("Failed to store comment feedback: %s", e.getMessage());
        }
    }

    /**
     * Returns all false-positive entries for a repo, newest first.
     */
    public List<CommentFeedbackEntry> findFalsePositives(String workspace, String repoSlug) {
        String sql = """
                SELECT id, comment_id, pr_id, workspace, repo_slug, feedback, category, pattern, created_by, created_at
                FROM comment_feedback
                WHERE workspace = ? AND repo_slug = ? AND feedback = 'false_positive'
                ORDER BY created_at DESC
                """;
        return query(sql, workspace, repoSlug);
    }

    /**
     * Returns false-positive counts grouped by finding category.
     */
    public Map<String, Long> countFalsePositivesByCategory(String workspace, String repoSlug) {
        String sql = """
                SELECT category, COUNT(*) AS cnt
                FROM comment_feedback
                WHERE workspace = ? AND repo_slug = ? AND feedback = 'false_positive'
                GROUP BY category
                ORDER BY cnt DESC
                """;
        Map<String, Long> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cat = rs.getString("category");
                    result.put(cat != null ? cat : "Uncategorised", rs.getLong("cnt"));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to count FP by category for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return result;
    }

    /**
     * Returns distinct patterns that have been marked as false-positive at least
     * {@code threshold} times for this repo.
     */
    public List<String> findRecurringPatterns(String workspace, String repoSlug, int threshold) {
        String sql = """
                SELECT pattern
                FROM comment_feedback
                WHERE workspace = ? AND repo_slug = ? AND feedback = 'false_positive'
                  AND pattern IS NOT NULL AND pattern <> ''
                GROUP BY pattern
                HAVING COUNT(*) >= ?
                ORDER BY COUNT(*) DESC
                """;
        List<String> patterns = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setInt(3, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    patterns.add(rs.getString("pattern"));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find recurring FP patterns for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return patterns;
    }

    /** Total number of FP entries for a repo. */
    public long countFalsePositives(String workspace, String repoSlug) {
        String sql = """
                SELECT COUNT(*) FROM comment_feedback
                WHERE workspace = ? AND repo_slug = ? AND feedback = 'false_positive'
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to count FPs for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return 0;
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private List<CommentFeedbackEntry> query(String sql, String workspace, String repoSlug) {
        List<CommentFeedbackEntry> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query comment_feedback for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    private CommentFeedbackEntry mapRow(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        return new CommentFeedbackEntry(
                rs.getLong("id"),
                rs.getLong("comment_id"),
                rs.getString("pr_id"),
                rs.getString("workspace"),
                rs.getString("repo_slug"),
                rs.getString("feedback"),
                rs.getString("category"),
                rs.getString("pattern"),
                rs.getString("created_by"),
                ts != null ? ts.toInstant() : null
        );
    }

    private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }
}
