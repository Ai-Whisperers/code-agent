package com.eneve.agent.aikido;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.*;

/**
 * PostgreSQL-backed cache for Aikido issue group detail responses.
 *
 * <p>Rows are keyed by {@code group_id} and upserted on every fetch or webhook event.
 * This avoids repeated calls to {@code GET /api/public/v1/issues/groups/{id}} during
 * snapshot rebuilds, which would quickly exhaust Aikido's 20 req/min rate limit.
 */
@ApplicationScoped
public class AikidoGroupDetailStore {

    private static final Logger LOG = Logger.getLogger(AikidoGroupDetailStore.class);

    @Inject AgroalDataSource dataSource;
    @Inject ObjectMapper mapper;

    // ─── Read ──────────────────────────────────────────────────────────────────

    /** Returns the cached detail for the given group, or {@code empty} if not cached. */
    public Optional<AikidoIssueInfo> find(int groupId) {
        String sql = """
                SELECT group_id, issue_type, title, description, severity, severity_score,
                       package_name, current_version, fixed_version, cve_id, cve_description,
                       cvss_score, repo_name, repo_url, container_image, how_to_fix,
                       related_cve_ids, group_status, time_to_fix_minutes
                FROM aikido_group_detail_cache
                WHERE group_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.warnf("AikidoGroupDetailStore.find(%d) failed: %s", groupId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns all cached group IDs that are present in the given set.
     * Used to determine which groups already have detail cached vs. need a fresh fetch.
     */
    public Set<Integer> findCachedIds(Collection<Integer> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return Set.of();
        Set<Integer> cached = new LinkedHashSet<>();
        String sql = """
                SELECT group_id FROM aikido_group_detail_cache
                WHERE group_id = ANY(?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Array arr = conn.createArrayOf("integer",
                    groupIds.stream().map(Object.class::cast).toArray());
            ps.setArray(1, arr);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cached.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            LOG.warnf("AikidoGroupDetailStore.findCachedIds failed: %s", e.getMessage());
        }
        return cached;
    }

    // ─── Write ─────────────────────────────────────────────────────────────────

    /** Inserts or updates a group detail entry. */
    public void upsert(AikidoIssueInfo info) {
        String sql = """
                INSERT INTO aikido_group_detail_cache
                    (group_id, issue_type, title, description, severity, severity_score,
                     package_name, current_version, fixed_version, cve_id, cve_description,
                     cvss_score, repo_name, repo_url, container_image, how_to_fix,
                     related_cve_ids, group_status, time_to_fix_minutes,
                     fetched_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, now(), now())
                ON CONFLICT (group_id) DO UPDATE SET
                    issue_type          = EXCLUDED.issue_type,
                    title               = EXCLUDED.title,
                    description         = EXCLUDED.description,
                    severity            = EXCLUDED.severity,
                    severity_score      = EXCLUDED.severity_score,
                    package_name        = EXCLUDED.package_name,
                    current_version     = EXCLUDED.current_version,
                    fixed_version       = EXCLUDED.fixed_version,
                    cve_id              = EXCLUDED.cve_id,
                    cve_description     = EXCLUDED.cve_description,
                    cvss_score          = EXCLUDED.cvss_score,
                    repo_name           = EXCLUDED.repo_name,
                    repo_url            = EXCLUDED.repo_url,
                    container_image     = EXCLUDED.container_image,
                    how_to_fix          = EXCLUDED.how_to_fix,
                    related_cve_ids     = EXCLUDED.related_cve_ids,
                    group_status        = EXCLUDED.group_status,
                    time_to_fix_minutes = EXCLUDED.time_to_fix_minutes,
                    updated_at          = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, info.issueGroupId());
            setNullable(ps, 2, info.issueType());
            setNullable(ps, 3, info.title());
            setNullable(ps, 4, info.description());
            setNullable(ps, 5, info.severity());
            setNullableInt(ps, 6, info.severityScore());
            setNullable(ps, 7, info.packageName());
            setNullable(ps, 8, info.currentVersion());
            setNullable(ps, 9, info.fixedVersion());
            setNullable(ps, 10, info.cveId());
            setNullable(ps, 11, info.cveDescription());
            if (info.cvssScore() != null) ps.setDouble(12, info.cvssScore());
            else ps.setNull(12, Types.NUMERIC);
            setNullable(ps, 13, info.repoName());
            setNullable(ps, 14, info.repoUrl());
            setNullable(ps, 15, info.containerImage());
            setNullable(ps, 16, info.howToFix());
            ps.setString(17, toJson(info.relatedCveIds()));
            setNullable(ps, 18, info.groupStatus());
            setNullableInt(ps, 19, info.timeToFixMinutes());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("AikidoGroupDetailStore.upsert(%d) failed: %s", info.issueGroupId(), e.getMessage());
        }
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private AikidoIssueInfo mapRow(ResultSet rs) throws SQLException {
        return new AikidoIssueInfo(
                rs.getInt("group_id"),
                rs.getString("issue_type"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("severity"),
                rs.getObject("severity_score", Integer.class),
                rs.getString("package_name"),
                rs.getString("current_version"),
                rs.getString("fixed_version"),
                rs.getString("cve_id"),
                rs.getString("cve_description"),
                rs.getObject("cvss_score") != null ? rs.getDouble("cvss_score") : null,
                rs.getString("repo_name"),
                rs.getString("repo_url"),
                rs.getString("container_image"),
                null,   // changelogSummary not stored
                rs.getString("how_to_fix"),
                fromJson(rs.getString("related_cve_ids")),
                rs.getString("group_status"),
                rs.getObject("time_to_fix_minutes", Integer.class),
                null,   // firstDetectedAt — not stored in detail cache; comes from export
                null    // slaRemediateBy  — not stored in detail cache; comes from export
        );
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try { return mapper.writeValueAsString(list); }
        catch (Exception e) { return "[]"; }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private void setNullable(PreparedStatement ps, int i, String v) throws SQLException {
        if (v != null) ps.setString(i, v); else ps.setNull(i, Types.VARCHAR);
    }

    private void setNullableInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v != null) ps.setInt(i, v); else ps.setNull(i, Types.INTEGER);
    }
}
