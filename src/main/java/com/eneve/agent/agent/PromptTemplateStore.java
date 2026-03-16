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

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * PostgreSQL-backed store for user-supplied prompt template overrides.
 * Only overridden templates are stored here; the JSON defaults file is the fallback.
 */
@ApplicationScoped
public class PromptTemplateStore {

    private static final Logger LOG = Logger.getLogger(PromptTemplateStore.class);

    @Inject
    AgroalDataSource dataSource;

    public Optional<PromptTemplate> find(String promptKey) {
        String sql = "SELECT prompt_key, content, description, updated_at FROM prompt_templates WHERE prompt_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, promptKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find prompt template '%s': %s", promptKey, e.getMessage());
        }
        return Optional.empty();
    }

    public List<PromptTemplate> listAll() {
        String sql = "SELECT prompt_key, content, description, updated_at FROM prompt_templates ORDER BY prompt_key";
        List<PromptTemplate> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list prompt templates: %s", e.getMessage());
        }
        return results;
    }

    public void upsert(String promptKey, String content, String description) {
        String sql = """
                INSERT INTO prompt_templates (prompt_key, content, description, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (prompt_key)
                DO UPDATE SET content     = EXCLUDED.content,
                              description = EXCLUDED.description,
                              updated_at  = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, promptKey);
            ps.setString(2, content);
            if (description != null) {
                ps.setString(3, description);
            } else {
                ps.setNull(3, Types.VARCHAR);
            }
            ps.executeUpdate();
            LOG.debugf("Upserted prompt template override for key '%s'", promptKey);
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert prompt template '%s': %s", promptKey, e.getMessage());
        }
    }

    public boolean delete(String promptKey) {
        String sql = "DELETE FROM prompt_templates WHERE prompt_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, promptKey);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOG.debugf("Deleted prompt template override for key '%s'", promptKey);
            }
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete prompt template '%s': %s", promptKey, e.getMessage());
            return false;
        }
    }

    private PromptTemplate mapRow(ResultSet rs) throws SQLException {
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        return new PromptTemplate(
                rs.getString("prompt_key"),
                rs.getString("content"),
                rs.getString("description"),
                updatedTs != null ? updatedTs.toInstant() : null
        );
    }
}
