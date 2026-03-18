package com.eneve.agent.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * PostgreSQL-backed store for AI call telemetry.
 * Logs every Anthropic API call with token usage, timing, and job context.
 */
@ApplicationScoped
public class AiCallStore {

    private static final Logger LOG = Logger.getLogger(AiCallStore.class);

    @Inject
    AgroalDataSource dataSource;

    @ConfigProperty(name = "anthropic.pricing.input-per-million", defaultValue = "3.0")
    double priceInputPerMillion;

    @ConfigProperty(name = "anthropic.pricing.output-per-million", defaultValue = "15.0")
    double priceOutputPerMillion;

    @ConfigProperty(name = "anthropic.pricing.cache-write-per-million", defaultValue = "3.75")
    double priceCacheWritePerMillion;

    @ConfigProperty(name = "anthropic.pricing.cache-read-per-million", defaultValue = "0.30")
    double priceCacheReadPerMillion;

    public void save(AiCallRecord record) {
        String sql = """
                INSERT INTO ai_calls
                    (job_id, job_type, model, iteration,
                     input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens,
                     stop_reason, tool_names, duration_ms, is_error, error_message, created_at,
                     prompt_text, response_text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setNullableString(ps, 1, record.jobId());
            setNullableString(ps, 2, record.jobType());
            ps.setString(3, record.model());
            if (record.iteration() != null) {
                ps.setInt(4, record.iteration());
            } else {
                ps.setNull(4, Types.INTEGER);
            }
            ps.setLong(5, record.inputTokens());
            ps.setLong(6, record.outputTokens());
            ps.setLong(7, record.cacheCreationInputTokens());
            ps.setLong(8, record.cacheReadInputTokens());
            setNullableString(ps, 9, record.stopReason());
            setNullableString(ps, 10, record.toolNames());
            ps.setLong(11, record.durationMs());
            ps.setBoolean(12, record.isError());
            setNullableString(ps, 13, record.errorMessage());
            ps.setTimestamp(14, Timestamp.from(record.createdAt() != null ? record.createdAt() : Instant.now()));
            setNullableString(ps, 15, record.promptText());
            setNullableString(ps, 16, record.responseText());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to store AI call record: %s", e.getMessage());
        }
    }

    public List<AiCallRecord> findByJobId(String jobId) {
        String sql = """
                SELECT id, job_id, job_type, model, iteration,
                       input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens,
                       stop_reason, tool_names, duration_ms, is_error, error_message, created_at,
                       prompt_text, response_text
                FROM ai_calls WHERE job_id = ?
                ORDER BY iteration ASC NULLS FIRST, created_at ASC
                """;
        List<AiCallRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query AI calls by jobId %s: %s", jobId, e.getMessage());
        }
        return results;
    }

    public List<AiCallRecord> getRecentCalls(int limit, int offset, String jobType,
                                              Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, job_id, job_type, model, iteration,
                       input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens,
                       stop_reason, tool_names, duration_ms, is_error, error_message, created_at,
                       prompt_text, response_text
                FROM ai_calls WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, jobType, from, to);
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<AiCallRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query recent AI calls: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Returns aggregated stats grouped by job_type and model.
     */
    public List<Map<String, Object>> getSummary(Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT job_type, model,
                       COUNT(*) AS call_count,
                       SUM(input_tokens) AS total_input_tokens,
                       SUM(output_tokens) AS total_output_tokens,
                       SUM(cache_creation_input_tokens) AS total_cache_write_tokens,
                       SUM(cache_read_input_tokens) AS total_cache_read_tokens,
                       SUM(duration_ms) AS total_duration_ms,
                       AVG(duration_ms) AS avg_duration_ms,
                       COUNT(DISTINCT job_id) AS unique_jobs,
                       SUM(CASE WHEN is_error THEN 1 ELSE 0 END) AS error_count
                FROM ai_calls WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        appendTimeFilters(sql, params, from, to);
        sql.append(" GROUP BY job_type, model ORDER BY call_count DESC");

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("jobType", rs.getString("job_type"));
                    row.put("model", rs.getString("model"));
                    row.put("callCount", rs.getLong("call_count"));
                    row.put("totalInputTokens", rs.getLong("total_input_tokens"));
                    row.put("totalOutputTokens", rs.getLong("total_output_tokens"));
                    row.put("totalCacheWriteTokens", rs.getLong("total_cache_write_tokens"));
                    row.put("totalCacheReadTokens", rs.getLong("total_cache_read_tokens"));
                    row.put("totalDurationMs", rs.getLong("total_duration_ms"));
                    row.put("avgDurationMs", rs.getDouble("avg_duration_ms"));
                    row.put("uniqueJobs", rs.getLong("unique_jobs"));
                    row.put("errorCount", rs.getLong("error_count"));
                    row.put("estimatedCostUsd", estimateCost(
                            rs.getLong("total_input_tokens"),
                            rs.getLong("total_output_tokens"),
                            rs.getLong("total_cache_write_tokens"),
                            rs.getLong("total_cache_read_tokens")));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query AI call summary: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Returns daily aggregated stats for time-series charts.
     */
    public List<Map<String, Object>> getDailySummary(Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT DATE(created_at AT TIME ZONE 'UTC') AS day,
                       COUNT(*) AS call_count,
                       SUM(input_tokens) AS total_input_tokens,
                       SUM(output_tokens) AS total_output_tokens,
                       SUM(cache_creation_input_tokens) AS total_cache_write_tokens,
                       SUM(cache_read_input_tokens) AS total_cache_read_tokens,
                       SUM(duration_ms) AS total_duration_ms,
                       COUNT(DISTINCT job_id) AS unique_jobs,
                       SUM(CASE WHEN is_error THEN 1 ELSE 0 END) AS error_count
                FROM ai_calls WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        appendTimeFilters(sql, params, from, to);
        sql.append(" GROUP BY day ORDER BY day ASC");

        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("day", rs.getString("day"));
                    row.put("callCount", rs.getLong("call_count"));
                    row.put("totalInputTokens", rs.getLong("total_input_tokens"));
                    row.put("totalOutputTokens", rs.getLong("total_output_tokens"));
                    row.put("totalCacheWriteTokens", rs.getLong("total_cache_write_tokens"));
                    row.put("totalCacheReadTokens", rs.getLong("total_cache_read_tokens"));
                    row.put("totalDurationMs", rs.getLong("total_duration_ms"));
                    row.put("uniqueJobs", rs.getLong("unique_jobs"));
                    row.put("errorCount", rs.getLong("error_count"));
                    row.put("estimatedCostUsd", estimateCost(
                            rs.getLong("total_input_tokens"),
                            rs.getLong("total_output_tokens"),
                            rs.getLong("total_cache_write_tokens"),
                            rs.getLong("total_cache_read_tokens")));
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query daily AI call summary: %s", e.getMessage());
        }
        return results;
    }

    public double estimateCost(long inputTokens, long outputTokens,
                               long cacheWriteTokens, long cacheReadTokens) {
        return (inputTokens * priceInputPerMillion
                + outputTokens * priceOutputPerMillion
                + cacheWriteTokens * priceCacheWritePerMillion
                + cacheReadTokens * priceCacheReadPerMillion) / 1_000_000.0;
    }

    private AiCallRecord mapRow(ResultSet rs) throws SQLException {
        Integer iteration = rs.getObject("iteration") != null ? rs.getInt("iteration") : null;
        Timestamp ts = rs.getTimestamp("created_at");
        return new AiCallRecord(
                rs.getLong("id"),
                rs.getString("job_id"),
                rs.getString("job_type"),
                rs.getString("model"),
                iteration,
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_creation_input_tokens"),
                rs.getLong("cache_read_input_tokens"),
                rs.getString("stop_reason"),
                rs.getString("tool_names"),
                rs.getLong("duration_ms"),
                rs.getBoolean("is_error"),
                rs.getString("error_message"),
                ts != null ? ts.toInstant() : null,
                rs.getString("prompt_text"),
                rs.getString("response_text")
        );
    }

    private void appendFilters(StringBuilder sql, List<Object> params,
                               String jobType, Instant from, Instant to) {
        if (jobType != null && !jobType.isBlank()) {
            sql.append(" AND job_type = ?");
            params.add(jobType);
        }
        appendTimeFilters(sql, params, from, to);
    }

    private void appendTimeFilters(StringBuilder sql, List<Object> params,
                                   Instant from, Instant to) {
        if (from != null) {
            sql.append(" AND created_at >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            params.add(Timestamp.from(to));
        }
    }

    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object param = params.get(i);
            if (param instanceof String s) {
                ps.setString(i + 1, s);
            } else if (param instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else if (param instanceof Long n) {
                ps.setLong(i + 1, n);
            } else if (param instanceof Timestamp ts) {
                ps.setTimestamp(i + 1, ts);
            } else {
                ps.setObject(i + 1, param);
            }
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
