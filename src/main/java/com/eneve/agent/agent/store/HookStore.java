package com.eneve.agent.agent.store;

import com.eneve.agent.agent.model.AutomationHook;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL-backed store for automation hooks.
 */
@ApplicationScoped
public class HookStore {

    private static final Logger LOG = Logger.getLogger(HookStore.class);
    @Inject ObjectMapper mapper;

    private static final String SELECT_COLS = """
            id, name, description, enabled, trigger_types, pr_event, branch_pattern,
            cron_expr, action_type, prompt, rule_names, extra_rules, target_branch,
            commit_direct, repo_url, trigger_filter, created_at, updated_at
            """;

    @Inject
    AgroalDataSource dataSource;

    public List<AutomationHook> listAll() {
        String sql = "SELECT " + SELECT_COLS + " FROM automation_hooks ORDER BY name";
        List<AutomationHook> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list automation hooks: %s", e.getMessage());
        }
        return results;
    }

    public Optional<AutomationHook> findByName(String name) {
        String sql = "SELECT " + SELECT_COLS + " FROM automation_hooks WHERE name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find hook '%s': %s", name, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns all enabled hooks matching the given trigger type and PR event.
     */
    public List<AutomationHook> findByTrigger(String triggerType, String prEvent) {
        String sql = "SELECT " + SELECT_COLS
                + " FROM automation_hooks WHERE enabled = TRUE AND trigger_types @> ?::jsonb AND pr_event = ?";
        List<AutomationHook> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "[\"" + triggerType + "\"]");
            ps.setString(2, prEvent);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find hooks for trigger %s/%s: %s", triggerType, prEvent, e.getMessage());
        }
        return results;
    }

    /**
     * Returns all enabled hooks matching the given trigger type.
     */
    public List<AutomationHook> findByTriggerType(String triggerType) {
        String sql = "SELECT " + SELECT_COLS
                + " FROM automation_hooks WHERE enabled = TRUE AND trigger_types @> ?::jsonb";
        List<AutomationHook> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "[\"" + triggerType + "\"]");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find hooks for trigger type %s: %s", triggerType, e.getMessage());
        }
        return results;
    }

    public void upsert(AutomationHook hook) {
        String sql = """
                INSERT INTO automation_hooks
                    (name, description, enabled, trigger_types, pr_event, branch_pattern,
                     cron_expr, action_type, prompt, rule_names, extra_rules, target_branch,
                     commit_direct, repo_url, trigger_filter, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now(), now())
                ON CONFLICT (name)
                DO UPDATE SET description    = EXCLUDED.description,
                              enabled        = EXCLUDED.enabled,
                              trigger_types  = EXCLUDED.trigger_types,
                              pr_event       = EXCLUDED.pr_event,
                              branch_pattern = EXCLUDED.branch_pattern,
                              cron_expr      = EXCLUDED.cron_expr,
                              action_type    = EXCLUDED.action_type,
                              prompt         = EXCLUDED.prompt,
                              rule_names     = EXCLUDED.rule_names,
                              extra_rules    = EXCLUDED.extra_rules,
                              target_branch  = EXCLUDED.target_branch,
                              commit_direct  = EXCLUDED.commit_direct,
                              repo_url       = EXCLUDED.repo_url,
                              trigger_filter = EXCLUDED.trigger_filter,
                              updated_at     = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hook.name());
            setNullableString(ps, 2, hook.description());
            ps.setBoolean(3, hook.enabled());
            setNullableJsonb(ps, 4, toJson(hook.triggerTypes()));
            setNullableString(ps, 5, hook.prEvent());
            setNullableString(ps, 6, hook.branchPattern());
            setNullableString(ps, 7, hook.cronExpr());
            ps.setString(8, hook.actionType());
            ps.setString(9, hook.prompt());
            setNullableString(ps, 10, toJson(hook.ruleNames()));
            setNullableString(ps, 11, hook.extraRules());
            setNullableString(ps, 12, hook.targetBranch());
            ps.setBoolean(13, hook.commitDirect());
            setNullableString(ps, 14, hook.repoUrl());
            setNullableJsonb(ps, 15, toJsonMap(hook.triggerFilter()));
            ps.executeUpdate();
            LOG.debugf("Upserted automation hook '%s'", hook.name());
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert hook '%s': %s", hook.name(), e.getMessage());
        }
    }

    public boolean delete(String name) {
        String sql = "DELETE FROM automation_hooks WHERE name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOG.debugf("Deleted automation hook '%s'", name);
            }
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete hook '%s': %s", name, e.getMessage());
            return false;
        }
    }

    public void setEnabled(String name, boolean enabled) {
        String sql = "UPDATE automation_hooks SET enabled = ?, updated_at = now() WHERE name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to set enabled=%s for hook '%s': %s", enabled, name, e.getMessage());
        }
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private AutomationHook mapRow(ResultSet rs) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        return new AutomationHook(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("enabled"),
                fromJson(rs.getString("trigger_types")),
                rs.getString("pr_event"),
                rs.getString("branch_pattern"),
                rs.getString("cron_expr"),
                rs.getString("action_type"),
                rs.getString("prompt"),
                fromJson(rs.getString("rule_names")),
                rs.getString("extra_rules"),
                rs.getString("target_branch"),
                rs.getBoolean("commit_direct"),
                rs.getString("repo_url"),
                fromJsonMap(rs.getString("trigger_filter")),
                createdTs != null ? createdTs.toInstant() : null,
                updatedTs != null ? updatedTs.toInstant() : null
        );
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String toJsonMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }

    /** Use for jsonb columns — passes NULL with type OTHER so the ::jsonb cast is satisfied. */
    private void setNullableJsonb(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.OTHER);
        }
    }
}
