package com.eneve.agent.agent.store;

import com.eneve.agent.agent.model.WebDocSource;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CRUD store for {@link WebDocSource} rows in the {@code web_doc_sources} table.
 */
@ApplicationScoped
public class WebDocSourceStore {

    private static final Logger LOG = Logger.getLogger(WebDocSourceStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<WebDocSource> listAll() {
        String sql = """
                SELECT id, name, base_url, allowed_path_prefix, max_pages, crawl_delay_ms,
                       last_crawled_at, last_crawl_chunks, last_crawl_error, created_at
                FROM web_doc_sources
                ORDER BY created_at DESC
                """;
        List<WebDocSource> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list web_doc_sources: %s", e.getMessage());
        }
        return results;
    }

    public Optional<WebDocSource> findById(String id) {
        String sql = """
                SELECT id, name, base_url, allowed_path_prefix, max_pages, crawl_delay_ms,
                       last_crawled_at, last_crawl_chunks, last_crawl_error, created_at
                FROM web_doc_sources WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find web_doc_source %s: %s", id, e.getMessage());
        }
        return Optional.empty();
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Inserts a new web doc source. The {@code id} and {@code createdAt} fields
     * on the returned record are filled in by the database.
     */
    public Optional<WebDocSource> insert(String name, String baseUrl, String allowedPathPrefix,
                                          int maxPages, int crawlDelayMs) {
        String sql = """
                INSERT INTO web_doc_sources
                    (name, base_url, allowed_path_prefix, max_pages, crawl_delay_ms)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, name, base_url, allowed_path_prefix, max_pages, crawl_delay_ms,
                          last_crawled_at, last_crawl_chunks, last_crawl_error, created_at
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, baseUrl);
            ps.setString(3, allowedPathPrefix);
            ps.setInt(4, maxPages);
            ps.setInt(5, crawlDelayMs);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to insert web_doc_source %s: %s", baseUrl, e.getMessage());
        }
        return Optional.empty();
    }

    public boolean delete(String id) {
        String sql = "DELETE FROM web_doc_sources WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete web_doc_source %s: %s", id, e.getMessage());
            return false;
        }
    }

    /**
     * Updates the crawl result columns after a crawl completes.
     *
     * @param id       UUID of the source
     * @param chunks   number of chunks indexed (0 if crawl yielded nothing)
     * @param errorMsg error message to store, or {@code null} on success
     */
    public void updateCrawlResult(String id, int chunks, String errorMsg) {
        String sql = """
                UPDATE web_doc_sources
                SET last_crawled_at   = now(),
                    last_crawl_chunks = ?,
                    last_crawl_error  = ?
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, chunks);
            setNullable(ps, 2, errorMsg);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to update crawl result for web_doc_source %s: %s", id, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static WebDocSource mapRow(ResultSet rs) throws SQLException {
        Timestamp lastCrawledAt = rs.getTimestamp("last_crawled_at");
        int lastCrawlChunks = rs.getInt("last_crawl_chunks");
        Integer lastCrawlChunksVal = rs.wasNull() ? null : lastCrawlChunks;
        return new WebDocSource(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("base_url"),
                rs.getString("allowed_path_prefix"),
                rs.getInt("max_pages"),
                rs.getInt("crawl_delay_ms"),
                lastCrawledAt != null ? lastCrawledAt.toInstant() : null,
                lastCrawlChunksVal,
                rs.getString("last_crawl_error"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static void setNullable(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value != null) {
            ps.setString(idx, value);
        } else {
            ps.setNull(idx, Types.VARCHAR);
        }
    }
}
