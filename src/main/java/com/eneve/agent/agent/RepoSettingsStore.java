package com.eneve.agent.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * PostgreSQL-backed store for per-repository settings.
 * Controls review enablement, shared rule selection, and custom prompt templates.
 */
@ApplicationScoped
public class RepoSettingsStore {

    private static final Logger LOG = Logger.getLogger(RepoSettingsStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    AgroalDataSource dataSource;

    public Optional<RepoSettings> find(String workspace, String repoSlug) {
        String sql = """
                SELECT id, workspace, repo_slug, review_enabled, vector_enabled, docs_enabled,
                       rule_names, review_prompt, disabled_hooks,
                       confluence_space_key, confluence_parent_page_id, created_at, updated_at
                FROM repo_settings
                WHERE workspace = ? AND repo_slug = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find repo settings for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return Optional.empty();
    }

    public List<RepoSettings> listAll() {
        String sql = """
                SELECT id, workspace, repo_slug, review_enabled, vector_enabled, docs_enabled,
                       rule_names, review_prompt, disabled_hooks,
                       confluence_space_key, confluence_parent_page_id, created_at, updated_at
                FROM repo_settings
                ORDER BY workspace, repo_slug
                """;
        List<RepoSettings> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list repo settings: %s", e.getMessage());
        }
        return results;
    }

    public void upsert(String workspace, String repoSlug, boolean reviewEnabled,
                       boolean vectorEnabled, boolean docsEnabled,
                       List<String> ruleNames, String reviewPrompt,
                       List<String> disabledHooks,
                       String confluenceSpaceKey, String confluenceParentPageId) {
        String sql = """
                INSERT INTO repo_settings
                    (workspace, repo_slug, review_enabled, vector_enabled, docs_enabled,
                     rule_names, review_prompt, disabled_hooks,
                     confluence_space_key, confluence_parent_page_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (workspace, repo_slug)
                DO UPDATE SET review_enabled           = EXCLUDED.review_enabled,
                              vector_enabled           = EXCLUDED.vector_enabled,
                              docs_enabled             = EXCLUDED.docs_enabled,
                              rule_names               = EXCLUDED.rule_names,
                              review_prompt            = EXCLUDED.review_prompt,
                              disabled_hooks           = EXCLUDED.disabled_hooks,
                              confluence_space_key     = EXCLUDED.confluence_space_key,
                              confluence_parent_page_id = EXCLUDED.confluence_parent_page_id,
                              updated_at               = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setBoolean(3, reviewEnabled);
            ps.setBoolean(4, vectorEnabled);
            ps.setBoolean(5, docsEnabled);
            setNullableString(ps, 6, toJson(ruleNames));
            setNullableString(ps, 7, reviewPrompt);
            setNullableString(ps, 8, toJson(disabledHooks));
            setNullableString(ps, 9, confluenceSpaceKey);
            setNullableString(ps, 10, confluenceParentPageId);
            ps.executeUpdate();
            LOG.debugf("Upserted repo settings for %s/%s (reviewEnabled=%s, vectorEnabled=%s, docsEnabled=%s)",
                    workspace, repoSlug, reviewEnabled, vectorEnabled, docsEnabled);
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert repo settings for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
    }

    /**
     * Inserts default settings only if the repo does not already have a row.
     * Returns true if a new row was inserted.
     */
    public boolean insertIfAbsent(String workspace, String repoSlug) {
        String sql = """
                INSERT INTO repo_settings (workspace, repo_slug, review_enabled, created_at, updated_at)
                VALUES (?, ?, TRUE, now(), now())
                ON CONFLICT (workspace, repo_slug) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to insert default settings for %s/%s: %s", workspace, repoSlug, e.getMessage());
            return false;
        }
    }

    public boolean delete(String workspace, String repoSlug) {
        String sql = "DELETE FROM repo_settings WHERE workspace = ? AND repo_slug = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOG.debugf("Deleted repo settings for %s/%s", workspace, repoSlug);
            }
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete repo settings for %s/%s: %s", workspace, repoSlug, e.getMessage());
            return false;
        }
    }

    /**
     * Returns {@code true} if the repo is enabled for review.
     * When no settings row exists the repo is considered enabled (opt-out model).
     */
    public boolean isReviewEnabled(String workspace, String repoSlug) {
        String sql = "SELECT review_enabled FROM repo_settings WHERE workspace = ? AND repo_slug = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("review_enabled");
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check review enabled for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return true;
    }

    /**
     * Returns {@code true} if the given hook is explicitly disabled for this repo.
     * When no settings row exists the hook is considered enabled.
     */
    public boolean isHookDisabled(String workspace, String repoSlug, String hookName) {
        String sql = "SELECT disabled_hooks FROM repo_settings WHERE workspace = ? AND repo_slug = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    List<String> disabled = fromJson(rs.getString("disabled_hooks"));
                    return disabled.contains(hookName);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check disabled hooks for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return false;
    }

    public boolean isVectorEnabled(String workspace, String repoSlug) {
        String sql = "SELECT vector_enabled FROM repo_settings WHERE workspace = ? AND repo_slug = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("vector_enabled");
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check vector enabled for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return false;
    }

    public void setVectorEnabled(String workspace, String repoSlug, boolean enabled) {
        String sql = """
                UPDATE repo_settings SET vector_enabled = ?, updated_at = now()
                WHERE workspace = ? AND repo_slug = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setString(2, workspace);
            ps.setString(3, repoSlug);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to set vector_enabled for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
    }

    public boolean isDocsEnabled(String workspace, String repoSlug) {
        String sql = "SELECT docs_enabled FROM repo_settings WHERE workspace = ? AND repo_slug = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("docs_enabled");
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check docs enabled for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return true;
    }

    public void setDocsEnabled(String workspace, String repoSlug, boolean enabled) {
        String sql = """
                UPDATE repo_settings SET docs_enabled = ?, updated_at = now()
                WHERE workspace = ? AND repo_slug = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setString(2, workspace);
            ps.setString(3, repoSlug);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to set docs_enabled for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
    }

    public void setReviewEnabled(String workspace, String repoSlug, boolean enabled) {
        String sql = """
                UPDATE repo_settings SET review_enabled = ?, updated_at = now()
                WHERE workspace = ? AND repo_slug = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setString(2, workspace);
            ps.setString(3, repoSlug);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to set review_enabled for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private RepoSettings mapRow(ResultSet rs) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        return new RepoSettings(
                rs.getLong("id"),
                rs.getString("workspace"),
                rs.getString("repo_slug"),
                rs.getBoolean("review_enabled"),
                rs.getBoolean("vector_enabled"),
                rs.getBoolean("docs_enabled"),
                fromJson(rs.getString("rule_names")),
                rs.getString("review_prompt"),
                fromJson(rs.getString("disabled_hooks")),
                rs.getString("confluence_space_key"),
                rs.getString("confluence_parent_page_id"),
                createdTs != null ? createdTs.toInstant() : null,
                updatedTs != null ? updatedTs.toInstant() : null
        );
    }

    private static String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialize rule names: %s", e.getMessage());
            return null;
        }
    }

    private static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to parse rule names JSON: %s", e.getMessage());
            return List.of();
        }
    }

    private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }
}
