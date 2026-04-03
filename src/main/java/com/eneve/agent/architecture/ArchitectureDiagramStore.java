package com.eneve.agent.architecture;

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
 * PostgreSQL-backed store for versioned architecture diagrams.
 *
 * <p>Every write inserts a new immutable row — no in-place updates.
 * "Current" version = pinned if one exists, otherwise the highest version number.
 */
@ApplicationScoped
public class ArchitectureDiagramStore {

    private static final Logger LOG = Logger.getLogger(ArchitectureDiagramStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ── Repo diagrams ─────────────────────────────────────────────────────────

    /** Returns the current (pinned or latest) version for every view of the given repo. */
    public List<ArchitectureDiagramVersion> findCurrentVersions(String repoSlug) {
        String sql = """
                SELECT DISTINCT ON (view_name)
                    id, repo_slug, customer_id, environment,
                    view_name, view_type, version, source, pinned, dsl_src, mermaid_src, created_at
                FROM architecture_diagram_versions
                WHERE repo_slug = ?
                ORDER BY view_name,
                         pinned DESC,   -- pinned rows first
                         version DESC   -- then latest
                """;
        return queryList(sql, repoSlug);
    }

    /** Returns the full DSL of the pinned version for a repo; falls back to the latest version. */
    public Optional<String> findPinnedDsl(String repoSlug) {
        String sql = """
                SELECT dsl_src
                FROM architecture_diagram_versions
                WHERE repo_slug = ?
                ORDER BY pinned DESC, version DESC
                LIMIT 1
                """;
        return queryDsl(sql, repoSlug);
    }

    /** Returns all versions for a specific repo view, newest first. */
    public List<ArchitectureDiagramVersion> listVersions(String repoSlug, String viewName) {
        String sql = """
                SELECT id, repo_slug, customer_id, environment,
                       view_name, view_type, version, source, pinned, dsl_src, mermaid_src, created_at
                FROM architecture_diagram_versions
                WHERE repo_slug = ? AND view_name = ?
                ORDER BY version DESC
                """;
        return queryListTwo(sql, repoSlug, viewName);
    }

    /** Returns all distinct repo slugs that have at least one diagram version. */
    public List<String> listRepoSlugs() {
        String sql = """
                SELECT DISTINCT repo_slug
                FROM architecture_diagram_versions
                WHERE repo_slug IS NOT NULL
                ORDER BY repo_slug
                """;
        return queryStringList(sql);
    }

    /**
     * Returns the most-recently stored repo_url for each distinct repo_slug.
     * Rows without a repo_url (generated before V83 migration) are excluded.
     */
    public List<String> listRepoUrls() {
        String sql = """
                SELECT DISTINCT ON (repo_slug) repo_url
                FROM architecture_diagram_versions
                WHERE repo_slug IS NOT NULL AND repo_url IS NOT NULL
                ORDER BY repo_slug, id DESC
                """;
        return queryStringList(sql);
    }

    // ── Cloud diagrams ────────────────────────────────────────────────────────

    /** Returns the current (pinned or latest) version for every view of the given cloud environment. */
    public List<ArchitectureDiagramVersion> findCurrentCloudVersions(String customerId, String environment) {
        String sql = """
                SELECT DISTINCT ON (view_name)
                    id, repo_slug, customer_id, environment,
                    view_name, view_type, version, source, pinned, dsl_src, mermaid_src, created_at
                FROM architecture_diagram_versions
                WHERE customer_id = ? AND environment = ?
                ORDER BY view_name,
                         pinned DESC,
                         version DESC
                """;
        return queryListTwo(sql, customerId, environment);
    }

    /** Returns the full DSL of the pinned version for a cloud environment; falls back to latest. */
    public Optional<String> findPinnedCloudDsl(String customerId, String environment) {
        String sql = """
                SELECT dsl_src
                FROM architecture_diagram_versions
                WHERE customer_id = ? AND environment = ?
                ORDER BY pinned DESC, version DESC
                LIMIT 1
                """;
        return queryDslTwo(sql, customerId, environment);
    }

    /** Returns all versions for a specific cloud environment view, newest first. */
    public List<ArchitectureDiagramVersion> listCloudVersions(String customerId, String environment, String viewName) {
        String sql = """
                SELECT id, repo_slug, customer_id, environment,
                       view_name, view_type, version, source, pinned, dsl_src, mermaid_src, created_at
                FROM architecture_diagram_versions
                WHERE customer_id = ? AND environment = ? AND view_name = ?
                ORDER BY version DESC
                """;
        return queryListThree(sql, customerId, environment, viewName);
    }

    /** Returns all distinct (customerId, environment) pairs that have at least one diagram version. */
    public List<CloudEnvironmentKey> listCloudEnvironments() {
        String sql = """
                SELECT DISTINCT customer_id, environment
                FROM architecture_diagram_versions
                WHERE customer_id IS NOT NULL
                ORDER BY customer_id, environment
                """;
        List<CloudEnvironmentKey> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(new CloudEnvironmentKey(rs.getString("customer_id"), rs.getString("environment")));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list cloud environments: %s", e.getMessage());
        }
        return results;
    }

    // ── Shared write operations ───────────────────────────────────────────────

    /**
     * Inserts a new version row for a repo diagram.
     * The version number is auto-incremented as MAX(version)+1 for the given scope+view_name.
     */
    public long insertRepoVersion(String repoSlug, String repoUrl, String viewName, String viewType,
                                  String source, String dslSrc, String mermaidSrc) {
        String sql = """
                INSERT INTO architecture_diagram_versions
                    (repo_slug, repo_url, view_name, view_type, version, source, pinned, dsl_src, mermaid_src)
                VALUES (?, ?, ?, ?, COALESCE(
                    (SELECT MAX(version) FROM architecture_diagram_versions
                     WHERE repo_slug = ? AND view_name = ?), 0) + 1,
                    ?, false, ?, ?)
                RETURNING id
                """;
        return insertReturningId(sql, repoSlug, repoUrl, viewName, viewType, repoSlug, viewName, source, dslSrc, mermaidSrc);
    }

    /** Backwards-compatible overload without repoUrl (human edits, etc.). */
    public long insertRepoVersion(String repoSlug, String viewName, String viewType,
                                  String source, String dslSrc, String mermaidSrc) {
        return insertRepoVersion(repoSlug, null, viewName, viewType, source, dslSrc, mermaidSrc);
    }

    /**
     * Inserts a new version row for a cloud environment diagram.
     */
    public long insertCloudVersion(String customerId, String environment, String viewName, String viewType,
                                   String source, String dslSrc, String mermaidSrc) {
        String sql = """
                INSERT INTO architecture_diagram_versions
                    (customer_id, environment, view_name, view_type, version, source, pinned, dsl_src, mermaid_src)
                VALUES (?, ?, ?, ?, COALESCE(
                    (SELECT MAX(version) FROM architecture_diagram_versions
                     WHERE customer_id = ? AND environment = ? AND view_name = ?), 0) + 1,
                    ?, false, ?, ?)
                RETURNING id
                """;
        return insertReturningId(sql, customerId, environment, viewName, viewType,
                customerId, environment, viewName, source, dslSrc, mermaidSrc);
    }

    /**
     * Pins the given version row and unpins any previously pinned row for the same scope+view_name.
     * Returns true if the row was found and pinned.
     */
    public boolean pin(long id) {
        // Fetch scope info for the target row first
        String selectSql = """
                SELECT repo_slug, customer_id, environment, view_name
                FROM architecture_diagram_versions WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement sel = conn.prepareStatement(selectSql)) {
            sel.setLong(1, id);
            try (ResultSet rs = sel.executeQuery()) {
                if (!rs.next()) return false;
                String repoSlug = rs.getString("repo_slug");
                String customerId = rs.getString("customer_id");
                String environment = rs.getString("environment");
                String viewName = rs.getString("view_name");

                // Unpin all existing pins for this scope+view_name
                String unpinSql;
                if (repoSlug != null) {
                    unpinSql = "UPDATE architecture_diagram_versions SET pinned = false WHERE repo_slug = ? AND view_name = ?";
                    try (PreparedStatement unpin = conn.prepareStatement(unpinSql)) {
                        unpin.setString(1, repoSlug);
                        unpin.setString(2, viewName);
                        unpin.executeUpdate();
                    }
                } else {
                    unpinSql = "UPDATE architecture_diagram_versions SET pinned = false WHERE customer_id = ? AND environment = ? AND view_name = ?";
                    try (PreparedStatement unpin = conn.prepareStatement(unpinSql)) {
                        unpin.setString(1, customerId);
                        unpin.setString(2, environment);
                        unpin.setString(3, viewName);
                        unpin.executeUpdate();
                    }
                }

                // Pin the target row
                String pinSql = "UPDATE architecture_diagram_versions SET pinned = true WHERE id = ?";
                try (PreparedStatement pinPs = conn.prepareStatement(pinSql)) {
                    pinPs.setLong(1, id);
                    int rows = pinPs.executeUpdate();
                    LOG.debugf("Pinned architecture diagram version id=%d", id);
                    return rows > 0;
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to pin architecture diagram version %d: %s", id, e.getMessage());
            return false;
        }
    }

    /** Unpins the given version row. */
    public boolean unpin(long id) {
        String sql = "UPDATE architecture_diagram_versions SET pinned = false WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            int rows = ps.executeUpdate();
            LOG.debugf("Unpinned architecture diagram version id=%d", id);
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to unpin architecture diagram version %d: %s", id, e.getMessage());
            return false;
        }
    }

    /** Fetches a single version row by its primary key. */
    public Optional<ArchitectureDiagramVersion> findById(long id) {
        String sql = """
                SELECT id, repo_slug, customer_id, environment,
                       view_name, view_type, version, source, pinned, dsl_src, mermaid_src, created_at
                FROM architecture_diagram_versions WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to fetch architecture diagram version %d: %s", id, e.getMessage());
        }
        return Optional.empty();
    }

    // ── Row mapper ────────────────────────────────────────────────────────────

    private ArchitectureDiagramVersion mapRow(ResultSet rs) throws SQLException {
        return new ArchitectureDiagramVersion(
                rs.getLong("id"),
                rs.getString("repo_slug"),
                rs.getString("customer_id"),
                rs.getString("environment"),
                rs.getString("view_name"),
                rs.getString("view_type"),
                rs.getInt("version"),
                rs.getString("source"),
                rs.getBoolean("pinned"),
                rs.getString("dsl_src"),
                rs.getString("mermaid_src"),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    private List<ArchitectureDiagramVersion> queryList(String sql, String param1) {
        List<ArchitectureDiagramVersion> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Architecture diagram query failed: %s", e.getMessage());
        }
        return results;
    }

    private List<ArchitectureDiagramVersion> queryListTwo(String sql, String p1, String p2) {
        List<ArchitectureDiagramVersion> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p1);
            ps.setString(2, p2);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Architecture diagram query failed: %s", e.getMessage());
        }
        return results;
    }

    private List<ArchitectureDiagramVersion> queryListThree(String sql, String p1, String p2, String p3) {
        List<ArchitectureDiagramVersion> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p1);
            ps.setString(2, p2);
            ps.setString(3, p3);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Architecture diagram query failed: %s", e.getMessage());
        }
        return results;
    }

    private Optional<String> queryDsl(String sql, String param1) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param1);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString("dsl_src"));
            }
        } catch (SQLException e) {
            LOG.errorf("Architecture DSL query failed: %s", e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<String> queryDslTwo(String sql, String p1, String p2) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p1);
            ps.setString(2, p2);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString("dsl_src"));
            }
        } catch (SQLException e) {
            LOG.errorf("Architecture DSL query failed: %s", e.getMessage());
        }
        return Optional.empty();
    }

    private List<String> queryStringList(String sql) {
        List<String> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) results.add(rs.getString(1));
        } catch (SQLException e) {
            LOG.errorf("Architecture string-list query failed: %s", e.getMessage());
        }
        return results;
    }

    private long insertReturningId(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOG.errorf("Architecture diagram insert failed: %s", e.getMessage());
        }
        return -1L;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    /** Key identifying a unique cloud environment. */
    public record CloudEnvironmentKey(String customerId, String environment) {}
}
