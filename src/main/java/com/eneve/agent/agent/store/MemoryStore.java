package com.eneve.agent.agent.store;

import com.eneve.agent.agent.model.MemoryEntry;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-backed store for review memory entries.
 * Persists team preferences and learned patterns per repository so the
 * reviewer can adapt its behaviour across jobs.
 */
@ApplicationScoped
public class MemoryStore {

    private static final Logger LOG = Logger.getLogger(MemoryStore.class);

    @Inject
    AgroalDataSource dataSource;

    public void save(MemoryEntry entry) {
        String sql = """
                INSERT INTO review_memory
                    (workspace, repo_slug, memory_text, category, source,
                     source_comment_id, source_pr_id, is_active, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.workspace());
            ps.setString(2, entry.repoSlug());
            ps.setString(3, entry.memoryText());
            setNullableString(ps, 4, entry.category());
            ps.setString(5, entry.source());
            if (entry.sourceCommentId() != null) {
                ps.setLong(6, entry.sourceCommentId());
            } else {
                ps.setNull(6, Types.BIGINT);
            }
            setNullableString(ps, 7, entry.sourcePrId());
            ps.setBoolean(8, entry.isActive());
            ps.setTimestamp(9, Timestamp.from(
                    entry.createdAt() != null ? entry.createdAt() : Instant.now()));
            setNullableString(ps, 10, entry.createdBy());
            ps.executeUpdate();
            LOG.debugf("Stored review memory for %s/%s: %s",
                    entry.workspace(), entry.repoSlug(),
                    truncate(entry.memoryText(), 80));
        } catch (SQLException e) {
            LOG.errorf("Failed to store review memory: %s", e.getMessage());
        }
    }

    /**
     * Returns all active memories for a repository, ordered oldest-first
     * so the prompt section reads chronologically.
     */
    public List<MemoryEntry> findForRepo(String workspace, String repoSlug) {
        String sql = """
                SELECT id, workspace, repo_slug, memory_text, category, source,
                       source_comment_id, source_pr_id, is_active, created_at, created_by
                FROM review_memory
                WHERE workspace = ? AND repo_slug = ? AND is_active = TRUE
                ORDER BY created_at ASC
                """;
        return query(sql, workspace, repoSlug);
    }

    /**
     * Returns all memories (including inactive) for a repository — used by the management API.
     */
    public List<MemoryEntry> listAll(String workspace, String repoSlug) {
        String sql = """
                SELECT id, workspace, repo_slug, memory_text, category, source,
                       source_comment_id, source_pr_id, is_active, created_at, created_by
                FROM review_memory
                WHERE workspace = ? AND repo_slug = ?
                ORDER BY created_at DESC
                """;
        return query(sql, workspace, repoSlug);
    }

    /**
     * Returns all memories across all repositories — used by the global management UI.
     */
    public List<MemoryEntry> listAll() {
        String sql = """
                SELECT id, workspace, repo_slug, memory_text, category, source,
                       source_comment_id, source_pr_id, is_active, created_at, created_by
                FROM review_memory
                ORDER BY created_at DESC
                """;
        List<MemoryEntry> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list all review memories: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Toggles the {@code is_active} flag for a memory entry.
     * Returns {@code true} if a row was updated.
     */
    public boolean toggleActive(long id) {
        String sql = "UPDATE review_memory SET is_active = NOT is_active WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOG.debugf("Toggled active state for review memory id=%d", id);
            }
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to toggle review memory id=%d: %s", id, e.getMessage());
            return false;
        }
    }

    /**
     * Soft-deletes a memory by setting {@code is_active = false}.
     * Returns {@code true} if a row was updated.
     */
    public boolean deactivate(long id) {
        String sql = "UPDATE review_memory SET is_active = FALSE WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOG.debugf("Deactivated review memory id=%d", id);
            }
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to deactivate review memory id=%d: %s", id, e.getMessage());
            return false;
        }
    }

    /**
     * Checks whether an identical active memory already exists for this repo,
     * to avoid storing duplicates from repeated conversations.
     */
    public boolean exists(String workspace, String repoSlug, String memoryText) {
        String sql = """
                SELECT 1 FROM review_memory
                WHERE workspace = ? AND repo_slug = ? AND memory_text = ? AND is_active = TRUE
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, memoryText);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check review memory existence: %s", e.getMessage());
            return false;
        }
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private List<MemoryEntry> query(String sql, String workspace, String repoSlug) {
        List<MemoryEntry> results = new ArrayList<>();
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
            LOG.errorf("Failed to query review memories for %s/%s: %s",
                    workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    private MemoryEntry mapRow(ResultSet rs) throws SQLException {
        Long sourceCommentId = rs.getObject("source_comment_id") != null
                ? rs.getLong("source_comment_id") : null;
        Timestamp ts = rs.getTimestamp("created_at");
        return new MemoryEntry(
                rs.getLong("id"),
                rs.getString("workspace"),
                rs.getString("repo_slug"),
                rs.getString("memory_text"),
                rs.getString("category"),
                rs.getString("source"),
                sourceCommentId,
                rs.getString("source_pr_id"),
                rs.getBoolean("is_active"),
                ts != null ? ts.toInstant() : null,
                rs.getString("created_by")
        );
    }

    private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
