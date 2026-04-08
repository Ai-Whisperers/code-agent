package com.eneve.agent.agent.store;

import com.anthropic.models.messages.MessageParam;
import com.eneve.agent.agent.MessageSerializer;
import com.eneve.agent.agent.model.JobCheckpoint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anthropic.core.ObjectMappers;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.postgresql.util.PGobject;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists per-job conversation checkpoints (full message list + git commit SHA) to the
 * {@code job_checkpoints} table. Only the latest checkpoint per job is kept — every
 * {@link #save} call is an UPSERT.
 *
 * <p>Checkpoints are used by the restart flow to resume a FAILED job from its last known
 * good state instead of starting from scratch.
 */
@ApplicationScoped
public class JobCheckpointStore {

    private static final Logger LOG = Logger.getLogger(JobCheckpointStore.class);
    private static final ObjectMapper SDK_MAPPER = ObjectMappers.jsonMapper();

    @Inject
    AgroalDataSource dataSource;

    @Inject
    MessageSerializer serializer;

    /**
     * Upserts the checkpoint for {@code jobId}, replacing any prior checkpoint for the same job.
     *
     * @param jobId      the job being checkpointed
     * @param iteration  zero-based iteration index that just completed
     * @param messages   full accumulated message list at the end of this iteration
     * @param gitCommitSha git SHA pushed to the checkpoint branch, or "" if no workspace
     */
    public void save(String jobId, int iteration, List<MessageParam> messages, String gitCommitSha) {
        String sql = """
                INSERT INTO job_checkpoints (job_id, iteration, messages_json, git_commit_sha, checkpointed_at)
                VALUES (?, ?, ?::jsonb, ?, now())
                ON CONFLICT (job_id) DO UPDATE SET
                    iteration       = EXCLUDED.iteration,
                    messages_json   = EXCLUDED.messages_json,
                    git_commit_sha  = EXCLUDED.git_commit_sha,
                    checkpointed_at = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setInt(2, iteration);
            ps.setObject(3, buildPgJson(serializeMessages(messages)));
            ps.setString(4, gitCommitSha != null ? gitCommitSha : "");
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to save checkpoint for job %s at iteration %d: %s",
                    jobId, iteration, e.getMessage());
        }
    }

    /**
     * Returns the latest checkpoint for the given job, or empty if none exists.
     */
    public Optional<JobCheckpoint> load(String jobId) {
        String sql = """
                SELECT job_id, iteration, messages_json, git_commit_sha, checkpointed_at
                FROM job_checkpoints WHERE job_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String messagesJson = rs.getString("messages_json");
                    List<MessageParam> messages = deserializeMessages(messagesJson);
                    Timestamp ts = rs.getTimestamp("checkpointed_at");
                    return Optional.of(new JobCheckpoint(
                            rs.getString("job_id"),
                            rs.getInt("iteration"),
                            messages,
                            rs.getString("git_commit_sha"),
                            ts != null ? ts.toInstant() : Instant.EPOCH
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to load checkpoint for job %s: %s", jobId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns {@code true} if a checkpoint exists for the given job.
     */
    public boolean exists(String jobId) {
        String sql = "SELECT 1 FROM job_checkpoints WHERE job_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check checkpoint existence for job %s: %s", jobId, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes the checkpoint for the given job. Should be called when the job (or its
     * successful restart) reaches a terminal state.
     */
    public void delete(String jobId) {
        String sql = "DELETE FROM job_checkpoints WHERE job_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOG.infof("Deleted checkpoint for job %s", jobId);
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to delete checkpoint for job %s: %s", jobId, e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String serializeMessages(List<MessageParam> messages) {
        List<String> jsonList = new ArrayList<>(messages.size());
        for (MessageParam m : messages) {
            jsonList.add(serializer.toJson(m));
        }
        try {
            return SDK_MAPPER.writeValueAsString(jsonList);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise message list", e);
        }
    }

    private List<MessageParam> deserializeMessages(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> jsonList = SDK_MAPPER.readValue(json, new TypeReference<List<String>>() {});
            List<MessageParam> result = new ArrayList<>(jsonList.size());
            for (String item : jsonList) {
                result.add(serializer.fromJson(item));
            }
            return result;
        } catch (JsonProcessingException e) {
            LOG.errorf("Failed to deserialise message list: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    private PGobject buildPgJson(String json) throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        obj.setValue(json);
        return obj;
    }
}
