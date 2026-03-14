package com.eneve.agent.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.eneve.agent.model.FixPrRequest;
import com.eneve.agent.model.HookJobRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.ReplyCommentRequest;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.model.RunFixRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * PostgreSQL-backed store for job records, with an in-memory cache for fast access
 * to active jobs. Replaces the previous in-memory ConcurrentHashMap and the
 * file-based processed-JIRA-keys ledger.
 */
@ApplicationScoped
public class JobStore {

    private static final Logger LOG = Logger.getLogger(JobStore.class);

    @Inject
    AgroalDataSource dataSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** In-memory cache for fast access to recently seen / active jobs. */
    private final Map<String, JobRecord> cache = new ConcurrentHashMap<>();

    /**
     * Persist a new job to the database and add it to the cache.
     */
    public void put(JobRecord job) {
        String sql = """
                INSERT INTO jobs
                    (job_id, job_type, status, request_payload, created_at, updated_at,
                     summary, error_message, pr_url, pr_id, files_changed, lines_changed, jira_key)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (job_id) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, job.getJobId());
            ps.setString(2, job.getJobType().name());
            ps.setString(3, job.getStatus().name());
            ps.setString(4, serializeRequest(job));
            ps.setTimestamp(5, Timestamp.from(job.getCreatedAt()));
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            setNullable(ps, 7, job.getSummary());
            setNullable(ps, 8, job.getErrorMessage());
            setNullable(ps, 9, job.getPrUrl());
            setNullable(ps, 10, job.getPrId());
            ps.setInt(11, job.getFilesChanged());
            ps.setInt(12, job.getLinesChanged());
            setNullable(ps, 13, extractJiraKey(job));
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to insert job %s: %s", job.getJobId(), e.getMessage());
        }
        cache.put(job.getJobId(), job);
    }

    /**
     * Persist the mutable fields of a job to the database and update the cache.
     * Called after every state transition in AgentRunner.
     */
    public void update(JobRecord job) {
        String sql = """
                UPDATE jobs SET
                    status        = ?,
                    updated_at    = ?,
                    summary       = ?,
                    error_message = ?,
                    pr_url        = ?,
                    pr_id         = ?,
                    files_changed = ?,
                    lines_changed = ?
                WHERE job_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, job.getStatus().name());
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            setNullable(ps, 3, job.getSummary());
            setNullable(ps, 4, job.getErrorMessage());
            setNullable(ps, 5, job.getPrUrl());
            setNullable(ps, 6, job.getPrId());
            ps.setInt(7, job.getFilesChanged());
            ps.setInt(8, job.getLinesChanged());
            ps.setString(9, job.getJobId());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to update job %s: %s", job.getJobId(), e.getMessage());
        }
        cache.put(job.getJobId(), job);
    }

    /**
     * Retrieve a job by ID. Checks the cache first; falls back to the database for
     * completed or evicted jobs. This ensures /status/{jobId} works for all historical jobs.
     */
    public Optional<JobRecord> get(String jobId) {
        JobRecord cached = cache.get(jobId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return loadFromDb(jobId);
    }

    /**
     * Returns all jobs with the given status, ordered by created_at ascending.
     * Used for startup recovery.
     */
    public List<JobRecord> findByStatus(JobStatus status) {
        String sql = """
                SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed
                FROM jobs WHERE status = ? ORDER BY created_at ASC
                """;
        List<JobRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JobRecord job = mapRow(rs);
                    if (job != null) {
                        results.add(job);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query jobs by status %s: %s", status, e.getMessage());
        }
        return results;
    }

    /**
     * Returns true if there is at least one active job (PENDING, QUEUED, RUNNING, or
     * AWAITING_APPROVAL) for the given JIRA key.
     */
    public boolean hasActiveJobForJiraKey(String jiraKey) {
        String sql = """
                SELECT 1 FROM jobs
                WHERE jira_key = ?
                  AND status IN ('PENDING','QUEUED','RUNNING','AWAITING_APPROVAL')
                LIMIT 1
                """;
        return existsQuery(sql, jiraKey);
    }

    /**
     * Returns true if this JIRA key has ever been processed (any status).
     * Replaces the old file-backed processed-keys ledger.
     */
    public boolean hasEverBeenProcessed(String jiraKey) {
        String sql = "SELECT 1 FROM jobs WHERE jira_key = ? LIMIT 1";
        return existsQuery(sql, jiraKey);
    }

    /**
     * Returns all distinct JIRA keys that have ever been processed.
     */
    public Set<String> getProcessedKeys() {
        String sql = "SELECT DISTINCT jira_key FROM jobs WHERE jira_key IS NOT NULL";
        Set<String> keys = ConcurrentHashMap.newKeySet();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                keys.add(rs.getString("jira_key"));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query processed keys: %s", e.getMessage());
        }
        return keys;
    }

    /**
     * No-op: the jobs table now serves as the persistent ledger for processed JIRA keys.
     * Kept for API compatibility; callers can be cleaned up over time.
     */
    public void markJiraKeyProcessed(String jiraKey) {
        // Job insertion via put() already records the jira_key in the jobs table.
    }

    /**
     * Resets the status of a job in the DB back to QUEUED (used during startup recovery
     * when a job was interrupted mid-execution).
     */
    public void resetToQueued(JobRecord job) {
        job.setStatus(JobStatus.QUEUED);
        job.setErrorMessage(null);
        update(job);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Optional<JobRecord> loadFromDb(String jobId) {
        String sql = """
                SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed
                FROM jobs WHERE job_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    JobRecord job = mapRow(rs);
                    if (job != null) {
                        cache.put(jobId, job);
                        return Optional.of(job);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to load job %s from DB: %s", jobId, e.getMessage());
        }
        return Optional.empty();
    }

    private boolean existsQuery(String sql, String param) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.errorf("Existence query failed: %s", e.getMessage());
            return false;
        }
    }

    private JobRecord mapRow(ResultSet rs) throws SQLException {
        String jobId = rs.getString("job_id");
        JobType jobType;
        try {
            jobType = JobType.valueOf(rs.getString("job_type"));
        } catch (IllegalArgumentException e) {
            LOG.warnf("Unknown job_type for job %s, skipping", jobId);
            return null;
        }

        String payloadJson = rs.getString("request_payload");
        JobRecord job = deserializeToRecord(jobId, jobType, payloadJson);
        if (job == null) return null;

        JobStatus status;
        try {
            status = JobStatus.valueOf(rs.getString("status"));
        } catch (IllegalArgumentException e) {
            status = JobStatus.FAILED;
        }
        job.setStatus(status);
        job.setSummary(rs.getString("summary"));
        job.setErrorMessage(rs.getString("error_message"));
        job.setPrUrl(rs.getString("pr_url"));
        job.setPrId(rs.getString("pr_id"));
        job.setFilesChanged(rs.getInt("files_changed"));
        job.setLinesChanged(rs.getInt("lines_changed"));
        return job;
    }

    private JobRecord deserializeToRecord(String jobId, JobType jobType, String payloadJson) {
        try {
            return switch (jobType) {
                case FIX -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, RunFixRequest.class));
                case REVIEW -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, ReviewPrRequest.class));
                case FIX_PR -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, FixPrRequest.class));
                case REPLY -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, ReplyCommentRequest.class),
                        JobType.REPLY);
                case FIX_COMMENT -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, ReplyCommentRequest.class),
                        JobType.FIX_COMMENT);
                case HOOK -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, HookJobRequest.class));
            };
        } catch (Exception e) {
            LOG.errorf("Failed to deserialize request payload for job %s (type=%s): %s",
                    jobId, jobType, e.getMessage());
            return null;
        }
    }

    private String serializeRequest(JobRecord job) {
        Object request = switch (job.getJobType()) {
            case FIX -> job.getRequest();
            case REVIEW -> job.getReviewRequest();
            case FIX_PR -> job.getFixPrRequest();
            case REPLY, FIX_COMMENT -> job.getReplyRequest();
            case HOOK -> job.getHookRequest();
        };
        if (request == null) return "{}";
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            LOG.warnf("Failed to serialize request for job %s: %s", job.getJobId(), e.getMessage());
            return "{}";
        }
    }

    private static String extractJiraKey(JobRecord job) {
        if (job.getRequest() != null) return job.getRequest().jiraKey();
        if (job.getReviewRequest() != null) return job.getReviewRequest().jiraKey();
        if (job.getFixPrRequest() != null) return job.getFixPrRequest().jiraKey();
        return null;
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }
}
