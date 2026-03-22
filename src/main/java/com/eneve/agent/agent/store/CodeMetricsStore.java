package com.eneve.agent.agent.store;

import com.eneve.agent.agent.CodeMetricsCalculator.CodeMetricsSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed store for {@link CodeMetricsSnapshot} records.
 * Snapshots are serialised to JSONB in the {@code code_metrics_snapshots} table.
 */
@ApplicationScoped
public class CodeMetricsStore {

    private static final Logger LOG = Logger.getLogger(CodeMetricsStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Inject
    AgroalDataSource dataSource;

    /**
     * Persists a snapshot and returns the generated {@code snapshot_id}.
     *
     * @param snapshot the snapshot to store
     * @param planId   the plan this snapshot belongs to (may be {@code null})
     */
    public String save(CodeMetricsSnapshot snapshot, String planId) {
        String snapshotId = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO code_metrics_snapshots
                    (snapshot_id, plan_id, workspace, repo_slug, branch, threshold, snapshot_data, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, now())
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, snapshotId);
            setNullable(ps, 2, planId);
            ps.setString(3, snapshot.workspace());
            ps.setString(4, snapshot.repoSlug());
            ps.setString(5, snapshot.branch());
            ps.setInt(6, snapshot.threshold());
            ps.setString(7, toJson(snapshot));
            ps.executeUpdate();
            LOG.infof("CodeMetricsStore: saved snapshot %s for %s/%s (plan=%s)",
                    snapshotId, snapshot.workspace(), snapshot.repoSlug(), planId);
        } catch (SQLException e) {
            LOG.errorf("CodeMetricsStore: failed to save snapshot: %s", e.getMessage());
        }
        return snapshotId;
    }

    /**
     * Returns all snapshots for a given plan, ordered by creation time ascending.
     */
    public List<CodeMetricsSnapshot> findByPlan(String planId) {
        String sql = """
                SELECT snapshot_data
                FROM code_metrics_snapshots
                WHERE plan_id = ?
                ORDER BY created_at ASC
                """;
        List<CodeMetricsSnapshot> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CodeMetricsSnapshot snap = fromJson(rs.getString("snapshot_data"));
                    if (snap != null) results.add(snap);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("CodeMetricsStore: failed to query by plan %s: %s", planId, e.getMessage());
        }
        return results;
    }

    /**
     * Returns the most recent snapshot for the given plan, or empty if none exists.
     */
    public Optional<CodeMetricsSnapshot> findLatestByPlan(String planId) {
        String sql = """
                SELECT snapshot_data
                FROM code_metrics_snapshots
                WHERE plan_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(fromJson(rs.getString("snapshot_data")));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("CodeMetricsStore: failed to query latest for plan %s: %s", planId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns all snapshots for a repository, ordered by creation time descending.
     */
    public List<CodeMetricsSnapshot> findByRepo(String workspace, String repoSlug) {
        String sql = """
                SELECT snapshot_data
                FROM code_metrics_snapshots
                WHERE workspace = ? AND repo_slug = ?
                ORDER BY created_at DESC
                """;
        List<CodeMetricsSnapshot> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CodeMetricsSnapshot snap = fromJson(rs.getString("snapshot_data"));
                    if (snap != null) results.add(snap);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("CodeMetricsStore: failed to query by repo %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    // ─── Serialisation helpers ────────────────────────────────────────────

    private String toJson(CodeMetricsSnapshot snapshot) {
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            LOG.warnf("CodeMetricsStore: failed to serialise snapshot: %s", e.getMessage());
            return "{}";
        }
    }

    private CodeMetricsSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, CodeMetricsSnapshot.class);
        } catch (JsonProcessingException e) {
            LOG.warnf("CodeMetricsStore: failed to deserialise snapshot: %s", e.getMessage());
            return null;
        }
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }
}
