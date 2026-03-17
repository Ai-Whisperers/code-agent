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
                       upgrade_enabled, quality_report_enabled, archived, rule_names, review_prompt, disabled_hooks,
                       confluence_space_key, confluence_parent_page_id,
                       archetype, archetype_version, created_at, updated_at
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
                       upgrade_enabled, quality_report_enabled, archived, rule_names, review_prompt, disabled_hooks,
                       confluence_space_key, confluence_parent_page_id,
                       archetype, archetype_version, created_at, updated_at
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
                       boolean vectorEnabled, boolean docsEnabled, boolean upgradeEnabled,
                       boolean qualityReportEnabled, boolean archived,
                       List<String> ruleNames, String reviewPrompt,
                       List<String> disabledHooks,
                       String confluenceSpaceKey, String confluenceParentPageId) {
        String sql = """
                INSERT INTO repo_settings
                    (workspace, repo_slug, review_enabled, vector_enabled, docs_enabled,
                     upgrade_enabled, quality_report_enabled, archived, rule_names, review_prompt, disabled_hooks,
                     confluence_space_key, confluence_parent_page_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (workspace, repo_slug)
                DO UPDATE SET review_enabled           = EXCLUDED.review_enabled,
                              vector_enabled           = EXCLUDED.vector_enabled,
                              docs_enabled             = EXCLUDED.docs_enabled,
                              upgrade_enabled          = EXCLUDED.upgrade_enabled,
                              quality_report_enabled   = EXCLUDED.quality_report_enabled,
                              archived                 = EXCLUDED.archived,
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
            ps.setBoolean(6, upgradeEnabled);
            ps.setBoolean(7, qualityReportEnabled);
            ps.setBoolean(8, archived);
            setNullableString(ps, 9, toJson(ruleNames));
            setNullableString(ps, 10, reviewPrompt);
            setNullableString(ps, 11, toJson(disabledHooks));
            setNullableString(ps, 12, confluenceSpaceKey);
            setNullableString(ps, 13, confluenceParentPageId);
            ps.executeUpdate();
            LOG.debugf("Upserted repo settings for %s/%s (reviewEnabled=%s, vectorEnabled=%s, docsEnabled=%s, upgradeEnabled=%s, qualityReportEnabled=%s, archived=%s)",
                    workspace, repoSlug, reviewEnabled, vectorEnabled, docsEnabled, upgradeEnabled, qualityReportEnabled, archived);
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

    public void setUpgradeEnabled(String workspace, String repoSlug, boolean enabled) {
        String sql = """
                UPDATE repo_settings SET upgrade_enabled = ?, updated_at = now()
                WHERE workspace = ? AND repo_slug = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setString(2, workspace);
            ps.setString(3, repoSlug);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to set upgrade_enabled for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
    }

    /**
     * Stores the detected framework archetype and its version for a repository.
     * Called by {@code CodeGraphBuildService} after indexing completes.
     */
    public void updateArchetype(String workspace, String repoSlug, String archetype, String archetypeVersion) {
        String sql = """
                UPDATE repo_settings SET archetype = ?, archetype_version = ?, updated_at = now()
                WHERE workspace = ? AND repo_slug = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setNullableString(ps, 1, archetype);
            setNullableString(ps, 2, archetypeVersion);
            ps.setString(3, workspace);
            ps.setString(4, repoSlug);
            ps.executeUpdate();
            LOG.debugf("Updated archetype for %s/%s: %s %s", workspace, repoSlug, archetype, archetypeVersion);
        } catch (SQLException e) {
            LOG.errorf("Failed to update archetype for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
    }

    /**
     * Returns all repos whose detected archetype matches the given value (case-insensitive).
     * Only repos with a non-null archetype_version are returned (detection must have run).
     */
    public List<RepoSettings> listByArchetype(String archetype) {
        String sql = """
                SELECT id, workspace, repo_slug, review_enabled, vector_enabled, docs_enabled,
                       upgrade_enabled, quality_report_enabled, archived, rule_names, review_prompt, disabled_hooks,
                       confluence_space_key, confluence_parent_page_id,
                       archetype, archetype_version, created_at, updated_at
                FROM repo_settings
                WHERE lower(archetype) = lower(?) AND archetype_version IS NOT NULL
                ORDER BY workspace, repo_slug
                """;
        List<RepoSettings> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, archetype);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list repos by archetype '%s': %s", archetype, e.getMessage());
        }
        return results;
    }

    // ─── Private helpers ────────────────────────────────────────────────

    public boolean isQualityReportEnabled(String workspace, String repoSlug) {
        String sql = "SELECT quality_report_enabled FROM repo_settings WHERE workspace = ? AND repo_slug = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("quality_report_enabled");
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check quality_report_enabled for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return false;
    }

    public void setQualityReportEnabled(String workspace, String repoSlug, boolean enabled) {
        String sql = """
                UPDATE repo_settings SET quality_report_enabled = ?, updated_at = now()
                WHERE workspace = ? AND repo_slug = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setString(2, workspace);
            ps.setString(3, repoSlug);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to set quality_report_enabled for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
    }

    public void setArchived(String workspace, String repoSlug, boolean archived) {
        String sql = """
                UPDATE repo_settings SET archived = ?, updated_at = now()
                WHERE workspace = ? AND repo_slug = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, archived);
            ps.setString(2, workspace);
            ps.setString(3, repoSlug);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to set archived for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
    }

    public List<RepoSettings> listQualityReportEnabled() {
        String sql = """
                SELECT id, workspace, repo_slug, review_enabled, vector_enabled, docs_enabled,
                       upgrade_enabled, quality_report_enabled, archived, rule_names, review_prompt, disabled_hooks,
                       confluence_space_key, confluence_parent_page_id,
                       archetype, archetype_version, created_at, updated_at
                FROM repo_settings
                WHERE quality_report_enabled = TRUE AND archived = FALSE
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
            LOG.errorf("Failed to list quality-report-enabled repos: %s", e.getMessage());
        }
        return results;
    }

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
                rs.getBoolean("upgrade_enabled"),
                rs.getBoolean("quality_report_enabled"),
                rs.getBoolean("archived"),
                fromJson(rs.getString("rule_names")),
                rs.getString("review_prompt"),
                fromJson(rs.getString("disabled_hooks")),
                rs.getString("confluence_space_key"),
                rs.getString("confluence_parent_page_id"),
                rs.getString("archetype"),
                rs.getString("archetype_version"),
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
