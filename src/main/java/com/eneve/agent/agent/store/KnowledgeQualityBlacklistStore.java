package com.eneve.agent.agent.store;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * Persists knowledge-source entries that were rejected by the Claude quality
 * filter so that subsequent resyncs do not waste API calls re-evaluating them.
 *
 * <h3>Content-hash staleness detection</h3>
 * Each blacklist entry stores an MD5 of the issue text at rejection time.
 * When {@link #isBlacklisted} is called on a resync the caller passes the
 * current content hash. If the hash has changed (the issue was edited), the
 * entry is treated as <em>not</em> blacklisted, allowing the issue to be
 * re-evaluated and potentially promoted back into the index.
 *
 * <h3>Manual unblocking</h3>
 * Call {@link #remove} to manually lift a blacklist entry (e.g. after an
 * agent rewrites the ticket description).
 */
@ApplicationScoped
public class KnowledgeQualityBlacklistStore {

    private static final Logger LOG = Logger.getLogger(KnowledgeQualityBlacklistStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Check whether a knowledge source is currently blacklisted <em>with the
     * same content</em>.
     *
     * <p>Returns {@code true} only when a row exists for
     * ({@code sourceType}, {@code sourceId}) AND the stored content hash
     * matches {@code contentHash}.  A hash mismatch means the issue was
     * edited since it was last rejected, so it should be re-evaluated.
     *
     * @param sourceType  e.g. {@code "jira"}
     * @param sourceId    e.g. {@code "ENG-123"}
     * @param contentHash MD5 hex string of the current issue text
     *                    (see {@link #md5(String)})
     */
    public boolean isBlacklisted(String sourceType, String sourceId, String contentHash) {
        String sql = """
                SELECT content_hash FROM knowledge_quality_blacklist
                WHERE source_type = ? AND source_id = ?
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceType);
            ps.setString(2, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String storedHash = rs.getString(1);
                if (storedHash.equals(contentHash)) {
                    return true;
                }
                // Content changed — remove the stale entry so the issue gets re-evaluated
                LOG.debugf("Blacklist entry for %s/%s is stale (content changed) — removing", sourceType, sourceId);
                remove(sourceType, sourceId);
                return false;
            }
        } catch (SQLException e) {
            LOG.warnf("Failed to query quality blacklist for %s/%s: %s — treating as not blacklisted",
                    sourceType, sourceId, e.getMessage());
            return false;
        }
    }

    /**
     * Add or refresh a blacklist entry.  Uses an upsert so calling this
     * multiple times for the same source is safe.
     *
     * @param sourceType  e.g. {@code "jira"}
     * @param sourceId    e.g. {@code "ENG-123"}
     * @param contentHash MD5 hex string of the rejected issue text
     * @param reason      short label, e.g. {@code "claude-quality-filter"}
     */
    public void add(String sourceType, String sourceId, String contentHash, String reason) {
        String sql = """
                INSERT INTO knowledge_quality_blacklist
                    (source_type, source_id, reason, content_hash, rejected_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (source_type, source_id) DO UPDATE
                    SET reason       = EXCLUDED.reason,
                        content_hash = EXCLUDED.content_hash,
                        rejected_at  = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceType);
            ps.setString(2, sourceId);
            ps.setString(3, reason);
            ps.setString(4, contentHash);
            ps.executeUpdate();
            LOG.debugf("Blacklisted %s/%s (reason=%s)", sourceType, sourceId, reason);
        } catch (SQLException e) {
            LOG.errorf("Failed to blacklist %s/%s: %s", sourceType, sourceId, e.getMessage());
        }
    }

    /**
     * Remove a blacklist entry, allowing the source to be re-evaluated on the
     * next indexing run.
     */
    public void remove(String sourceType, String sourceId) {
        String sql = "DELETE FROM knowledge_quality_blacklist WHERE source_type = ? AND source_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceType);
            ps.setString(2, sourceId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOG.debugf("Removed blacklist entry for %s/%s", sourceType, sourceId);
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to remove blacklist entry for %s/%s: %s", sourceType, sourceId, e.getMessage());
        }
    }

    /**
     * Returns the total number of entries currently in the blacklist.
     * Useful for monitoring / stats endpoints.
     */
    public long count() {
        String sql = "SELECT COUNT(*) FROM knowledge_quality_blacklist";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            LOG.warnf("Failed to count blacklist entries: %s", e.getMessage());
            return -1;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Compute an MD5 hex digest of the given text.
     * Used to detect content changes between indexing runs.
     */
    public static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
