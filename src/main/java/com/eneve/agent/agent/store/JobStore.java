package com.eneve.agent.agent.store;

import com.eneve.agent.model.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.*;
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

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final int CACHE_MAX_SIZE = 5_000;

    // Lazily initialised after dataSource is available (post-construct).
    private JobQueryHelper queryHelper;

    /** Bounded LRU cache for fast access to recently seen / active jobs. */
    private final Map<String, JobRecord> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_MAX_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JobRecord> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            });

    @jakarta.annotation.PostConstruct
    void init() {
        queryHelper = new JobQueryHelper(dataSource, this::mapRow);
    }

    /**
     * Persist a new job to the database and add it to the cache.
     */
    public void put(JobRecord job) {
        String sql = """
                INSERT INTO jobs
                    (job_id, job_type, status, request_payload, created_at, updated_at,
                     summary, error_message, pr_url, pr_id, files_changed, lines_changed, jira_key,
                     pr_author, workspace, repo_slug, priority, coverage_data,
                     aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
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
            setNullable(ps, 14, job.getPrAuthor());
            setNullable(ps, 15, job.getWorkspace());
            setNullable(ps, 16, job.getRepoSlug());
            ps.setInt(17, job.getPriority());
            setNullable(ps, 18, serializeCoverageData(job));
            setNullable(ps, 19, job.getAikidoIssueId());
            setNullable(ps, 20, job.getFixBranchName());
            setNullable(ps, 21, job.getJiraIssueType());
            setNullable(ps, 22, job.getJiraPriority());
            if (job.getJiraCreatedAt() != null) {
                ps.setTimestamp(23, Timestamp.from(job.getJiraCreatedAt()));
            } else {
                ps.setNull(23, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            }
            ps.executeUpdate();
            cache.put(job.getJobId(), job);
        } catch (SQLException e) {
            LOG.errorf("Failed to insert job %s: %s", job.getJobId(), e.getMessage());
        }
    }

    /**
     * Move a terminal job (SUCCESS or FAILED) from the active `jobs` table into
     * `job_history` atomically. The job stays in the cache so that
     * /status/{jobId} continues to work immediately after completion.
     */
    public void archive(JobRecord job) {
        String insert = """
                INSERT INTO job_history
                    (job_id, job_type, status, request_payload, created_at, updated_at, archived_at,
                     summary, error_message, pr_url, pr_id, files_changed, lines_changed, jira_key,
                     pr_author, workspace, repo_slug, priority, coverage_data,
                     aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                     promotion_job_id, workspace_path)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (job_id) DO NOTHING
                """;
        String delete = "DELETE FROM jobs WHERE job_id = ?";
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ins = conn.prepareStatement(insert)) {
                ins.setString(1, job.getJobId());
                ins.setString(2, job.getJobType().name());
                ins.setString(3, job.getStatus().name());
                ins.setString(4, serializeRequest(job));
                ins.setTimestamp(5, Timestamp.from(job.getCreatedAt()));
                ins.setTimestamp(6, Timestamp.from(Instant.now()));
                setNullable(ins, 7, job.getSummary());
                setNullable(ins, 8, job.getErrorMessage());
                setNullable(ins, 9, job.getPrUrl());
                setNullable(ins, 10, job.getPrId());
                ins.setInt(11, job.getFilesChanged());
                ins.setInt(12, job.getLinesChanged());
                setNullable(ins, 13, extractJiraKey(job));
                setNullable(ins, 14, job.getPrAuthor());
                setNullable(ins, 15, job.getWorkspace());
                setNullable(ins, 16, job.getRepoSlug());
                ins.setInt(17, job.getPriority());
                setNullable(ins, 18, serializeCoverageData(job));
                setNullable(ins, 19, job.getAikidoIssueId());
                setNullable(ins, 20, job.getFixBranchName());
                setNullable(ins, 21, job.getJiraIssueType());
                setNullable(ins, 22, job.getJiraPriority());
                if (job.getJiraCreatedAt() != null) {
                    ins.setTimestamp(23, Timestamp.from(job.getJiraCreatedAt()));
                } else {
                    ins.setNull(23, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                }
                setNullable(ins, 24, job.getPromotionJobId());
                setNullable(ins, 25, job.getWorkspacePath());
                ins.executeUpdate();
            }
            try (PreparedStatement del = conn.prepareStatement(delete)) {
                del.setString(1, job.getJobId());
                del.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            LOG.errorf("Failed to archive job %s: %s", job.getJobId(), e.getMessage());
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
                    status           = ?,
                    updated_at       = ?,
                    summary          = ?,
                    error_message    = ?,
                    pr_url           = ?,
                    pr_id            = ?,
                    files_changed    = ?,
                    lines_changed    = ?,
                    priority         = ?,
                    coverage_data    = ?::jsonb,
                    aikido_issue_id  = ?,
                    fix_branch_name  = ?,
                    jira_issue_type  = ?,
                    jira_priority    = ?,
                    jira_created_at  = ?,
                    promotion_job_id = ?,
                    workspace_path   = ?
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
            ps.setInt(9, job.getPriority());
            setNullable(ps, 10, serializeCoverageData(job));
            setNullable(ps, 11, job.getAikidoIssueId());
            setNullable(ps, 12, job.getFixBranchName());
            setNullable(ps, 13, job.getJiraIssueType());
            setNullable(ps, 14, job.getJiraPriority());
            if (job.getJiraCreatedAt() != null) {
                ps.setTimestamp(15, Timestamp.from(job.getJiraCreatedAt()));
            } else {
                ps.setNull(15, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            }
            setNullable(ps, 16, job.getPromotionJobId());
            setNullable(ps, 17, job.getWorkspacePath());
            ps.setString(18, job.getJobId());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to update job %s: %s", job.getJobId(), e.getMessage());
        }
        cache.put(job.getJobId(), job);
    }

    /**
     * Retrieve a job by ID. Checks the cache first, then the active `jobs` table,
     * then `job_history`. This ensures /status/{jobId} works for all jobs including
     * completed ones that have been archived.
     */
    public Optional<JobRecord> get(String jobId) {
        JobRecord cached = cache.get(jobId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<JobRecord> active = loadFromTable("jobs", jobId);
        if (active.isPresent()) return active;
        return loadFromTable("job_history", jobId);
    }

    // ── Query methods — delegated to JobQueryHelper ───────────────────────────

    /** Returns all jobs with the given status, ordered by created_at ascending. Used for startup recovery. */
    public List<JobRecord> findByStatus(JobStatus status) { return queryHelper.findByStatus(status); }

    /**
     * Returns true if there is at least one active job (PENDING, QUEUED, RUNNING, or
     * AWAITING_APPROVAL) for the given Aikido issue group ID.
     */
    public boolean hasActiveJobForAikidoGroupId(String groupId) { return queryHelper.hasActiveJobForAikidoGroupId(groupId); }

    /** Returns true if there is already an active SELF_ANALYSIS job targeting the given failed job ID. */
    public boolean hasActiveSelfAnalysisForJob(String failedJobId) { return queryHelper.hasActiveSelfAnalysisForJob(failedJobId); }

    /** Returns true if a successful SELF_ANALYSIS job for the given failed job ID was archived within the cooldown window. */
    public boolean hasRecentSuccessfulSelfAnalysisForJob(String failedJobId, int cooldownHours) {
        return queryHelper.hasRecentSuccessfulSelfAnalysisForJob(failedJobId, cooldownHours);
    }

    /**
     * Finds the most recent job for an Aikido issue group, optionally scoped to a specific repository slug.
     */
    public JobRecord findLatestJobForAikidoGroupId(String groupId, String repoSlug) {
        return queryHelper.findLatestJobForAikidoGroupId(groupId, repoSlug);
    }

    /** @deprecated Use {@link #findLatestJobForAikidoGroupId(String, String)} with a repoSlug. */
    @Deprecated
    public JobRecord findLatestJobForAikidoGroupId(String groupId) {
        return queryHelper.findLatestJobForAikidoGroupId(groupId);
    }

    /**
     * Looks up the workspace_path recorded on the most recent FAILED job for the given branch name.
     * Used by RunFixHandler to reuse an already-cloned workspace instead of cloning again.
     */
    public String findPreservedWorkspacePath(String branchName) { return queryHelper.findPreservedWorkspacePath(branchName); }

    public boolean hasActiveJobForJiraKey(String jiraKey) { return queryHelper.hasActiveJobForJiraKey(jiraKey); }

    /** Returns true if this JIRA key has ever been processed (any status, either table). */
    public boolean hasEverBeenProcessed(String jiraKey) { return queryHelper.hasEverBeenProcessed(jiraKey); }

    /** Returns all distinct JIRA keys that have ever been processed (both tables). */
    public Set<String> getProcessedKeys() { return queryHelper.getProcessedKeys(); }

    /**
     * Search jobs across both the active {@code jobs} table and {@code job_history},
     * with optional filtering by status and/or job type.
     *
     * @param status  filter by a specific status, or {@code null} for all statuses
     * @param jobType filter by a specific job type, or {@code null} for all types
     * @param limit   maximum number of results (capped at 200)
     * @param offset  zero-based row offset for pagination
     */
    public List<JobStatusResponse> search(JobStatus status, JobType jobType, int limit, int offset) {
        return queryHelper.search(status, jobType, limit, offset);
    }

    /**
     * Returns true when an active review job exists for the given PR ID scoped to a specific workspace and repository.
     */
    public boolean hasActiveReviewJobForPr(String prId, String workspace, String repoSlug) {
        return queryHelper.hasActiveReviewJobForPr(prId, workspace, repoSlug);
    }

    /** Returns all REVIEW jobs that are currently active. Used during boot reconciliation. */
    public List<JobRecord> findActiveReviewJobs() { return queryHelper.findActiveReviewJobs(); }

    /** Returns true when an active review job exists for the given Jira issue key. */
    public boolean hasActiveReviewJob(String issueKey) { return queryHelper.hasActiveReviewJob(issueKey); }

    /** Returns up to {@code limit} QUEUED roadmap review jobs, excluding IDs already in the dispatch queue. */
    public List<JobRecord> findQueuedReviewJobs(Set<String> excludeJobIds, int limit) {
        return queryHelper.findQueuedReviewJobs(excludeJobIds, limit);
    }

    /** Counts QUEUED or RUNNING roadmap review jobs associated with the given roadmap ID. */
    public long countActiveReviewJobsForRoadmap(String roadmapId) { return queryHelper.countActiveReviewJobsForRoadmap(roadmapId); }

    /**
     * Returns all jobs for a given PR ID scoped to a specific workspace and repository.
     * Use this overload from webhook handlers to avoid cross-repo collisions.
     */
    public List<JobRecord> findByPrId(String prId, String workspace, String repoSlug) {
        return queryHelper.findByPrId(prId, workspace, repoSlug);
    }

    /**
     * @deprecated Prefer {@link #findByPrId(String, String, String)} to avoid cross-repo collisions.
     */
    @Deprecated
    public List<JobRecord> findByPrId(String prId) { return queryHelper.findByPrId(prId); }

    /**
     * Returns all jobs (active + history) that have a non-null {@code jira_issue_type}, ordered newest first.
     */
    public List<JobRecord> findJobsWithJiraIssueType() { return queryHelper.findJobsWithJiraIssueType(); }

    /**
     * Returns {@code true} when the job is linked to a Jira issue type that appears in
     * the provided comma-separated bug types list. Used by the SOC II deletion guard.
     */
    public static boolean isSoc2Applicable(JobRecord job, String bugIssueTypes) {
        String issueType = job.getJiraIssueType();
        if (issueType == null || issueType.isBlank() || bugIssueTypes == null) return false;
        return java.util.Arrays.stream(bugIssueTypes.split("\\s*,\\s*"))
                .anyMatch(t -> t.equalsIgnoreCase(issueType));
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

    // ── Private helpers ───────────────────────────────────────────────────────

    private Optional<JobRecord> loadFromTable(String table, String jobId) {
        String sql = "SELECT job_id, job_type, status, request_payload, created_at, updated_at,"
                + " summary, error_message, pr_url, pr_id, files_changed, lines_changed,"
                + " pr_author, workspace, repo_slug, priority, coverage_data,"
                + " aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,"
                + " promotion_job_id, workspace_path"
                + " FROM " + table + " WHERE job_id = ?";
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
            LOG.errorf("Failed to load job %s from %s: %s", jobId, table, e.getMessage());
        }
        return Optional.empty();
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
        job.setPrAuthor(rs.getString("pr_author"));
        job.setWorkspace(rs.getString("workspace"));
        job.setRepoSlug(rs.getString("repo_slug"));
        int priority = rs.getInt("priority");
        if (!rs.wasNull()) {
            job.setPriority(priority);
        }
        try {
            String coverageJson = rs.getString("coverage_data");
            if (coverageJson != null) {
                job.setCoverageData(objectMapper.readValue(coverageJson, JobCoverageData.class));
            }
        } catch (Exception e) {
            LOG.warnf("Failed to deserialize coverage_data for job %s (non-fatal): %s", jobId, e.getMessage());
        }
        // Aikido / SLA / promotion fields — added in V68 migration, may be null for older rows
        try {
            job.setAikidoIssueId(rs.getString("aikido_issue_id"));
            job.setFixBranchName(rs.getString("fix_branch_name"));
            job.setJiraIssueType(rs.getString("jira_issue_type"));
            job.setJiraPriority(rs.getString("jira_priority"));
            Timestamp jiraCreatedAt = rs.getTimestamp("jira_created_at");
            if (jiraCreatedAt != null) job.setJiraCreatedAt(jiraCreatedAt.toInstant());
            job.setPromotionJobId(rs.getString("promotion_job_id"));
            job.setWorkspacePath(rs.getString("workspace_path"));
        } catch (Exception e) {
            LOG.warnf("Failed to read Aikido/SLA fields for job %s (non-fatal): %s", jobId, e.getMessage());
        }
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
                case GENERATE_TESTS -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, GenerateTestsRequest.class));
                case GENERATE_DOCS -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, GenerateDocsRequest.class));
                case SYNC_CONFLUENCE -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, SyncConfluenceRequest.class));
                case METRICS -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, MetricsJobRequest.class));
                case QUALITY_REPORT -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, QualityReportJobRequest.class));
                case REVIEW_EPIC, REVIEW_FEATURE, REVIEW_USERSTORY -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, JiraReviewRequest.class), jobType);
                case PROMOTE -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, PromoteRequest.class));
                case SELF_ANALYSIS -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, SelfAnalysisRequest.class));
                case GENERATE_ARCHITECTURE -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, GenerateArchitectureRequest.class));
                case GENERATE_CLOUD_ARCHITECTURE -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, GenerateCloudArchitectureRequest.class));
                case KNOWLEDGE_GRAPH -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, KnowledgeGraphRequest.class));
                case TECH_DEBT -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, TechDebtRequest.class));
                case REWRITE -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, RewriteRequest.class));
                case SERVICE_DESK_TRIAGE -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, ServiceDeskTriageRequest.class));
                case QA_TESTPLAN_ANALYSIS -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, QaTestPlanAnalysisRequest.class));
                case QA_TESTPLAN_CONVERSION -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, QaTestPlanConversionRequest.class));
                case QA_TESTCASE_GENERATION -> new JobRecord(jobId,
                        objectMapper.readValue(payloadJson, QaTestCaseGenerationRequest.class));
                case CHAT -> null;
            };
        } catch (Exception e) {
            LOG.errorf("Failed to deserialize request payload for job %s (type=%s): %s",
                    jobId, jobType, e.getMessage());
            return null;
        }
    }

    private String serializeRequest(JobRecord job) {
        JobPayload payload = job.getPayload();
        if (payload == null) return "{}";
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            LOG.warnf("Failed to serialize request for job %s: %s", job.getJobId(), e.getMessage());
            return "{}";
        }
    }

    private static String extractJiraKey(JobRecord job) {
        return switch (job.getPayload()) {
            case RunFixRequest r                 -> r.jiraKey();
            case ReviewPrRequest r               -> r.jiraKey();
            case FixPrRequest r                  -> r.jiraKey();
            case JiraReviewRequest r             -> r.issueKey();
            case PromoteRequest r                -> r.jiraKey();
            case QaTestPlanAnalysisRequest r     -> r.issueKey();
            case QaTestPlanConversionRequest r   -> r.issueKey();
            case QaTestCaseGenerationRequest r   -> r.issueKey();
            default                              -> null;
        };
    }

    private String serializeCoverageData(JobRecord job) {
        if (job.getCoverageData() == null) return null;
        try {
            return objectMapper.writeValueAsString(job.getCoverageData());
        } catch (Exception e) {
            LOG.warnf("Failed to serialize coverage_data for job %s: %s", job.getJobId(), e.getMessage());
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
