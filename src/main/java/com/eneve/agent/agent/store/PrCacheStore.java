package com.eneve.agent.agent.store;

import com.eneve.agent.model.OpenPrEntry;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-backed cache for open pull requests fetched from the SCM.
 * Rows are upserted on webhook events and replaced in bulk during full syncs.
 */
@ApplicationScoped
public class PrCacheStore {

    private static final Logger LOG = Logger.getLogger(PrCacheStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ── Upsert ────────────────────────────────────────────────────────────────

    public void upsert(OpenPrEntry pr) {
        String sql = """
                INSERT INTO open_pull_requests
                    (workspace, repo_slug, pr_id, pr_url, title,
                     source_branch, target_branch, author, status,
                     created_on, updated_on, soc2, cached_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (workspace, repo_slug, pr_id) DO UPDATE SET
                    pr_url        = EXCLUDED.pr_url,
                    title         = EXCLUDED.title,
                    source_branch = EXCLUDED.source_branch,
                    target_branch = EXCLUDED.target_branch,
                    author        = EXCLUDED.author,
                    status        = EXCLUDED.status,
                    created_on    = EXCLUDED.created_on,
                    updated_on    = EXCLUDED.updated_on,
                    soc2          = EXCLUDED.soc2,
                    cached_at     = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pr.workspace());
            ps.setString(2, pr.repoSlug());
            ps.setString(3, pr.prId());
            ps.setString(4, pr.prUrl());
            ps.setString(5, pr.title());
            ps.setString(6, pr.sourceBranch());
            ps.setString(7, pr.targetBranch());
            ps.setString(8, pr.author());
            ps.setString(9, pr.status() != null ? pr.status() : "OPEN");
            ps.setString(10, pr.createdOn());
            ps.setString(11, pr.updatedOn());
            ps.setBoolean(12, pr.soc2());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert PR %s/%s#%s: %s",
                    pr.workspace(), pr.repoSlug(), pr.prId(), e.getMessage());
        }
    }

    // ── Replace all PRs for a repo (used during full refresh) ─────────────────

    public void replaceForRepo(String workspace, String repoSlug, List<OpenPrEntry> prs) {
        String delete = "DELETE FROM open_pull_requests WHERE workspace = ? AND repo_slug = ? AND status = 'OPEN'";
        String insert = """
                INSERT INTO open_pull_requests
                    (workspace, repo_slug, pr_id, pr_url, title,
                     source_branch, target_branch, author, status,
                     created_on, updated_on, soc2, cached_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (workspace, repo_slug, pr_id) DO UPDATE SET
                    pr_url        = EXCLUDED.pr_url,
                    title         = EXCLUDED.title,
                    source_branch = EXCLUDED.source_branch,
                    target_branch = EXCLUDED.target_branch,
                    author        = EXCLUDED.author,
                    status        = EXCLUDED.status,
                    created_on    = EXCLUDED.created_on,
                    updated_on    = EXCLUDED.updated_on,
                    soc2          = EXCLUDED.soc2,
                    cached_at     = now()
                """;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(delete)) {
                    del.setString(1, workspace);
                    del.setString(2, repoSlug);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(insert)) {
                    for (OpenPrEntry pr : prs) {
                        ins.setString(1, pr.workspace());
                        ins.setString(2, pr.repoSlug());
                        ins.setString(3, pr.prId());
                        ins.setString(4, pr.prUrl());
                        ins.setString(5, pr.title());
                        ins.setString(6, pr.sourceBranch());
                        ins.setString(7, pr.targetBranch());
                        ins.setString(8, pr.author());
                        ins.setString(9, pr.status() != null ? pr.status() : "OPEN");
                        ins.setString(10, pr.createdOn());
                        ins.setString(11, pr.updatedOn());
                        ins.setBoolean(12, pr.soc2());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to replace PRs for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
    }

    // ── Search with optional free-text and status filter ──────────────────────

    public List<OpenPrEntry> search(String q, String status, int limit, int offset) {
        int safeLimit  = Math.min(Math.max(1, limit), 200);
        int safeOffset = Math.max(0, offset);

        String sql = buildSearchSql(false);
        List<OpenPrEntry> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindSearchParams(ps, q, status, safeLimit, safeOffset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to search PR cache: %s", e.getMessage());
        }
        return results;
    }

    public int count(String q, String status) {
        String sql = buildSearchSql(true);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindSearchParams(ps, q, status, 0, 0);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to count PR cache: %s", e.getMessage());
        }
        return 0;
    }

    // ── Staleness check ───────────────────────────────────────────────────────

    public Instant oldestCachedAt(String workspace, String repoSlug) {
        String sql = "SELECT MIN(cached_at) FROM open_pull_requests WHERE workspace = ? AND repo_slug = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    return ts != null ? ts.toInstant() : null;
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check cached_at for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return null;
    }

    // ── Single-row lookup ─────────────────────────────────────────────────────

    /**
     * Returns the cached entry for a specific PR, or {@code null} if not found.
     * Used during boot reconciliation to check whether a PR was already closed.
     */
    public OpenPrEntry findByPrId(String workspace, String repoSlug, String prId) {
        String sql = """
                SELECT workspace, repo_slug, pr_id, pr_url, title, source_branch, target_branch,
                       author, created_on, updated_on, status, soc2
                FROM open_pull_requests
                WHERE workspace = ? AND repo_slug = ? AND pr_id = ?
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, prId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            LOG.errorf("findByPrId(%s/%s#%s) failed: %s", workspace, repoSlug, prId, e.getMessage());
        }
        return null;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildSearchSql(boolean countOnly) {
        String select = countOnly
                ? "SELECT COUNT(*)"
                : "SELECT workspace, repo_slug, pr_id, pr_url, title, source_branch, target_branch, author, created_on, updated_on, status, soc2";
        String orderLimit = countOnly ? "" : " ORDER BY updated_on DESC NULLS LAST LIMIT ? OFFSET ?";
        return select + """
                 FROM open_pull_requests
                WHERE (? IS NULL OR to_tsvector('simple',
                           coalesce(title,'') || ' ' ||
                           coalesce(author,'') || ' ' ||
                           coalesce(repo_slug,''))
                       @@ plainto_tsquery('simple', ?))
                  AND (? IS NULL OR status = ?)
                """ + orderLimit;
    }

    private void bindSearchParams(PreparedStatement ps, String q, String status,
                                  int limit, int offset) throws SQLException {
        String qVal    = (q      != null && !q.isBlank())      ? q.trim() : null;
        String statVal = (status != null && !status.isBlank()) ? status   : null;

        ps.setString(1, qVal);
        ps.setString(2, qVal);
        ps.setString(3, statVal);
        ps.setString(4, statVal);

        if (limit > 0) {
            ps.setInt(5, limit);
            ps.setInt(6, offset);
        }
    }

    private OpenPrEntry mapRow(ResultSet rs) throws SQLException {
        return new OpenPrEntry(
                rs.getString("workspace"),
                rs.getString("repo_slug"),
                rs.getString("pr_id"),
                rs.getString("pr_url"),
                rs.getString("title"),
                rs.getString("source_branch"),
                rs.getString("target_branch"),
                rs.getString("author"),
                rs.getString("created_on"),
                rs.getString("updated_on"),
                null, // jobId: not stored in the pr_cache table; callers must look up via JobStore
                rs.getString("status"),
                rs.getBoolean("soc2")
        );
    }
}
