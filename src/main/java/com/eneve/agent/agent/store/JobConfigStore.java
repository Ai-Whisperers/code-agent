package com.eneve.agent.agent.store;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class JobConfigStore {

    private static final Logger LOG = Logger.getLogger(JobConfigStore.class);

    @Inject
    DataSource dataSource;

    public Optional<JobConfigRow> findByJobType(String jobType) {
        String sql = """
                SELECT job_type, model_tier, thinking_enabled, thinking_budget, max_output_tokens, updated_at
                FROM job_configurations WHERE job_type = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find job config for type %s: %s", jobType, e.getMessage());
        }
        return Optional.empty();
    }

    public List<JobConfigRow> findAll() {
        String sql = """
                SELECT job_type, model_tier, thinking_enabled, thinking_budget, max_output_tokens, updated_at
                FROM job_configurations ORDER BY job_type
                """;
        List<JobConfigRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(map(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list job configs: %s", e.getMessage());
        }
        return rows;
    }

    public void upsert(JobConfigRow row) {
        String sql = """
                INSERT INTO job_configurations
                    (job_type, model_tier, thinking_enabled, thinking_budget, max_output_tokens, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT (job_type) DO UPDATE SET
                    model_tier        = EXCLUDED.model_tier,
                    thinking_enabled  = EXCLUDED.thinking_enabled,
                    thinking_budget   = EXCLUDED.thinking_budget,
                    max_output_tokens = EXCLUDED.max_output_tokens,
                    updated_at        = NOW()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, row.jobType());
            ps.setString(2, row.modelTier());
            ps.setBoolean(3, row.thinkingEnabled());
            if (row.thinkingBudget() != null) {
                ps.setInt(4, row.thinkingBudget());
            } else {
                ps.setNull(4, Types.INTEGER);
            }
            if (row.maxOutputTokens() != null) {
                ps.setInt(5, row.maxOutputTokens());
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert job config for type %s: %s", row.jobType(), e.getMessage());
            throw new RuntimeException("Failed to save job configuration", e);
        }
    }

    public boolean delete(String jobType) {
        String sql = "DELETE FROM job_configurations WHERE job_type = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobType);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete job config for type %s: %s", jobType, e.getMessage());
            return false;
        }
    }

    private JobConfigRow map(ResultSet rs) throws SQLException {
        int thinkingBudgetRaw = rs.getInt("thinking_budget");
        Integer thinkingBudget = rs.wasNull() ? null : thinkingBudgetRaw;
        int maxOutputTokensRaw = rs.getInt("max_output_tokens");
        Integer maxOutputTokens = rs.wasNull() ? null : maxOutputTokensRaw;
        return new JobConfigRow(
                rs.getString("job_type"),
                rs.getString("model_tier"),
                rs.getBoolean("thinking_enabled"),
                thinkingBudget,
                maxOutputTokens,
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
