package com.eneve.agent.agent.store;

import com.eneve.agent.agent.model.StaticFileSource;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CRUD store for {@link StaticFileSource} rows in the {@code static_file_sources} table.
 */
@ApplicationScoped
public class StaticFileSourceStore {

    private static final Logger LOG = Logger.getLogger(StaticFileSourceStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<StaticFileSource> listAll() {
        String sql = """
                SELECT id, name, original_filename, content_type, s3_key, file_size,
                       indexed_at, chunk_count, index_error, created_at
                FROM static_file_sources
                ORDER BY created_at DESC
                """;
        List<StaticFileSource> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list static_file_sources: %s", e.getMessage());
        }
        return results;
    }

    public Optional<StaticFileSource> findById(String id) {
        String sql = """
                SELECT id, name, original_filename, content_type, s3_key, file_size,
                       indexed_at, chunk_count, index_error, created_at
                FROM static_file_sources WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find static_file_source %s: %s", id, e.getMessage());
        }
        return Optional.empty();
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Inserts a new static file source row. The {@code id} and {@code createdAt}
     * are filled in by the database.
     */
    public Optional<StaticFileSource> insert(String name, String originalFilename,
                                              String contentType, String s3Key, long fileSize) {
        String sql = """
                INSERT INTO static_file_sources
                    (name, original_filename, content_type, s3_key, file_size)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, name, original_filename, content_type, s3_key, file_size,
                          indexed_at, chunk_count, index_error, created_at
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, originalFilename);
            ps.setString(3, contentType);
            ps.setString(4, s3Key);
            ps.setLong(5, fileSize);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to insert static_file_source %s: %s", originalFilename, e.getMessage());
        }
        return Optional.empty();
    }

    public boolean delete(String id) {
        String sql = "DELETE FROM static_file_sources WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete static_file_source %s: %s", id, e.getMessage());
            return false;
        }
    }

    /**
     * Updates the indexing result columns after indexing completes.
     *
     * @param id       UUID of the source
     * @param chunks   number of chunks indexed (0 if nothing was indexed)
     * @param errorMsg error message to store, or {@code null} on success
     */
    public void updateIndexResult(String id, int chunks, String errorMsg) {
        String sql = """
                UPDATE static_file_sources
                SET indexed_at   = now(),
                    chunk_count  = ?,
                    index_error  = ?
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, chunks);
            setNullable(ps, 2, errorMsg);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to update index result for static_file_source %s: %s", id, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static StaticFileSource mapRow(ResultSet rs) throws SQLException {
        Timestamp indexedAt = rs.getTimestamp("indexed_at");
        int chunkCount = rs.getInt("chunk_count");
        Integer chunkCountVal = rs.wasNull() ? null : chunkCount;
        return new StaticFileSource(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getString("s3_key"),
                rs.getLong("file_size"),
                indexedAt != null ? indexedAt.toInstant() : null,
                chunkCountVal,
                rs.getString("index_error"),
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
