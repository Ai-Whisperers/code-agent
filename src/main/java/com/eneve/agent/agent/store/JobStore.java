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
import java.util.concurrent.ConcurrentHashMap;

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

    /** Bounded LRU cache for fast access to recently seen / active jobs. */
    private final Map<String, JobRecord> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_MAX_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JobRecord> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            });

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

    /**
     * Returns all jobs with the given status, ordered by created_at ascending.
     * Used for startup recovery.
     */
    public List<JobRecord> findByStatus(JobStatus status) {
        String sql = """
                SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed,
                       pr_author, workspace, repo_slug, priority, coverage_data,
                       aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                       promotion_job_id, workspace_path
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
    /**
     * Returns true if there is at least one active job (PENDING, QUEUED, RUNNING, or
     * AWAITING_APPROVAL) for the given Aikido issue group ID.
     * Used by AikidoTriageService to prevent duplicate fix jobs for the same vulnerability.
     */
    public boolean hasActiveJobForAikidoGroupId(String groupId) {
        String sql = """
                SELECT 1 FROM jobs
                WHERE aikido_issue_id = ?
                  AND status IN ('PENDING','QUEUED','RUNNING','AWAITING_APPROVAL')
                LIMIT 1
                """;
        return existsQuery(sql, groupId);
    }

    /**
     * Returns true if there is already an active (PENDING/QUEUED/RUNNING) SELF_ANALYSIS job
     * targeting the given failed job ID. Used by {@code SelfAnalysisTrigger} to prevent
     * duplicate submissions when the same failure event fires more than once.
     */
    public boolean hasActiveSelfAnalysisForJob(String failedJobId) {
        String sql = """
                SELECT 1 FROM jobs
                WHERE job_type = 'SELF_ANALYSIS'
                  AND status IN ('PENDING','QUEUED','RUNNING')
                  AND request_payload->>'failedJobId' = ?
                LIMIT 1
                """;
        return existsQuery(sql, failedJobId);
    }

    /**
     * Returns true if a successful (SUCCESS or AWAITING_APPROVAL) SELF_ANALYSIS job for the
     * given failed job ID was archived within the last {@code cooldownHours} hours.
     * Used by {@code SelfAnalysisTrigger} to enforce the cooldown window.
     */
    public boolean hasRecentSuccessfulSelfAnalysisForJob(String failedJobId, int cooldownHours) {
        String sql = """
                SELECT 1 FROM job_history
                WHERE job_type = 'SELF_ANALYSIS'
                  AND status IN ('SUCCESS','AWAITING_APPROVAL')
                  AND archived_at > now() - (? || ' hours')::interval
                  AND request_payload->>'failedJobId' = ?
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(cooldownHours));
            ps.setString(2, failedJobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.errorf("hasRecentSuccessfulSelfAnalysisForJob query failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Returns the most recent job (by created_at DESC) linked to the given Aikido issue group ID,
     * searching both the active jobs table and job_history. Returns {@code null} if none found.
     * Used by the security issues view to show linked job status.
     */
    /**
     * Finds the most recent job for an Aikido issue group, optionally scoped to a specific
     * repository slug. When {@code repoSlug} is non-null the query prefers an exact
     * repo_slug match; if no repo-scoped job exists it falls back to any job for that group.
     * This prevents a job created for repo A from appearing as the linked job for repo B
     * when both repos share the same Aikido vulnerability group ID.
     */
    public JobRecord findLatestJobForAikidoGroupId(String groupId, String repoSlug) {
        // Prefer repo-scoped match; fall back to any match for the group
        if (repoSlug != null && !repoSlug.isBlank()) {
            JobRecord scoped = findLatestJobForAikidoGroupAndRepo(groupId, repoSlug);
            if (scoped != null) return scoped;
        }
        return findLatestJobForAikidoGroupAndRepo(groupId, null);
    }

    /** @deprecated Use {@link #findLatestJobForAikidoGroupId(String, String)} with a repoSlug. */
    @Deprecated
    public JobRecord findLatestJobForAikidoGroupId(String groupId) {
        return findLatestJobForAikidoGroupId(groupId, null);
    }

    private JobRecord findLatestJobForAikidoGroupAndRepo(String groupId, String repoSlug) {
        boolean scoped = repoSlug != null && !repoSlug.isBlank();
        String repoFilter = scoped ? " AND repo_slug = ?" : "";
        String sql = "SELECT job_id, job_type, status, request_payload, created_at, updated_at,"
                + " summary, error_message, pr_url, pr_id, files_changed, lines_changed,"
                + " pr_author, workspace, repo_slug, priority, coverage_data,"
                + " aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,"
                + " promotion_job_id, workspace_path"
                + " FROM ("
                + "   SELECT job_id, job_type, status, request_payload, created_at, updated_at,"
                + "          summary, error_message, pr_url, pr_id, files_changed, lines_changed,"
                + "          pr_author, workspace, repo_slug, priority, coverage_data,"
                + "          aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,"
                + "          promotion_job_id, workspace_path"
                + "   FROM jobs WHERE aikido_issue_id = ?" + repoFilter
                + "   UNION ALL"
                + "   SELECT job_id, job_type, status, request_payload, created_at, updated_at,"
                + "          summary, error_message, pr_url, pr_id, files_changed, lines_changed,"
                + "          pr_author, workspace, repo_slug, priority, coverage_data,"
                + "          aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,"
                + "          promotion_job_id, workspace_path"
                + "   FROM job_history WHERE aikido_issue_id = ?" + repoFilter
                + " ) combined"
                + " ORDER BY created_at DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, groupId);
            if (scoped) ps.setString(idx++, repoSlug);
            ps.setString(idx++, groupId);
            if (scoped) ps.setString(idx, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            LOG.errorf("findLatestJobForAikidoGroupAndRepo(%s, %s) failed: %s", groupId, repoSlug, e.getMessage());
        }
        return null;
    }

    /**
     * Looks up the workspace_path recorded on the most recent FAILED job for the given
     * branch name. Searches both {@code job_history} (archived jobs) and the active
     * {@code jobs} table (jobs that failed but haven't been archived yet when the retry starts).
     * Returns {@code null} if no such job exists or it has no preserved path.
     * Used by RunFixHandler to reuse an already-cloned workspace instead of cloning again.
     */
    public String findPreservedWorkspacePath(String branchName) {
        if (branchName == null || branchName.isBlank()) return null;
        // Search job_history first (most common case — job is archived before retry)
        String historySql = """
                SELECT workspace_path FROM job_history
                WHERE fix_branch_name = ?
                  AND status = 'FAILED'
                  AND workspace_path IS NOT NULL
                ORDER BY archived_at DESC
                LIMIT 1
                """;
        // Also check the active jobs table in case the job failed but hasn't been archived yet
        String activeSql = """
                SELECT workspace_path FROM jobs
                WHERE fix_branch_name = ?
                  AND status = 'FAILED'
                  AND workspace_path IS NOT NULL
                ORDER BY updated_at DESC
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(historySql)) {
                ps.setString(1, branchName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String path = rs.getString("workspace_path");
                        if (path != null && !path.isBlank()) return path;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(activeSql)) {
                ps.setString(1, branchName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String path = rs.getString("workspace_path");
                        if (path != null && !path.isBlank()) return path;
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warnf("findPreservedWorkspacePath(%s) failed: %s", branchName, e.getMessage());
        }
        return null;
    }

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
     * Returns true if this JIRA key has ever been processed (any status, either table).
     * Replaces the old file-backed processed-keys ledger.
     */
    public boolean hasEverBeenProcessed(String jiraKey) {
        String sql = """
                SELECT 1 FROM jobs WHERE jira_key = ?
                UNION ALL
                SELECT 1 FROM job_history WHERE jira_key = ?
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jiraKey);
            ps.setString(2, jiraKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.errorf("Existence query failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Returns all distinct JIRA keys that have ever been processed (both tables).
     */
    public Set<String> getProcessedKeys() {
        String sql = """
                SELECT DISTINCT jira_key FROM jobs         WHERE jira_key IS NOT NULL
                UNION
                SELECT DISTINCT jira_key FROM job_history  WHERE jira_key IS NOT NULL
                """;
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
     * Search jobs across both the active {@code jobs} table and {@code job_history},
     * with optional filtering by status and/or job type.
     *
     * @param status  filter by a specific status, or {@code null} for all statuses
     * @param jobType filter by a specific job type, or {@code null} for all types
     * @param limit   maximum number of results (capped at 200)
     * @param offset  zero-based row offset for pagination
     * @return list of lightweight response objects ordered by {@code created_at DESC}
     */
    public List<JobStatusResponse> search(JobStatus status, JobType jobType, int limit, int offset) {
        int safeLimit = Math.min(Math.max(1, limit), 200);
        int safeOffset = Math.max(0, offset);

        StringBuilder where = new StringBuilder();
        List<String> params = new ArrayList<>();

        if (status != null) {
            where.append(" AND status = ?");
            params.add(status.name());
        }
        if (jobType != null) {
            where.append(" AND job_type = ?");
            params.add(jobType.name());
        }

        String cte = """
                SELECT job_id, job_type, status, created_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed, priority, jira_key
                FROM jobs WHERE 1=1
                """ + where + """

                UNION ALL
                SELECT job_id, job_type, status, created_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed, priority, jira_key
                FROM job_history WHERE 1=1
                """ + where;

        String sql = "SELECT * FROM (" + cte + ") combined ORDER BY created_at DESC LIMIT ? OFFSET ?";

        List<JobStatusResponse> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int idx = 1;
            for (String p : params) { ps.setString(idx++, p); }
            for (String p : params) { ps.setString(idx++, p); }
            ps.setInt(idx++, safeLimit);
            ps.setInt(idx, safeOffset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JobType type;
                    try {
                        type = JobType.valueOf(rs.getString("job_type"));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    JobStatus jobStatus;
                    try {
                        jobStatus = JobStatus.valueOf(rs.getString("status"));
                    } catch (IllegalArgumentException e) {
                        jobStatus = JobStatus.FAILED;
                    }
                    results.add(JobStatusResponse.fromSearch(
                            rs.getString("job_id"),
                            type,
                            jobStatus,
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("summary"),
                            rs.getString("error_message"),
                            rs.getString("pr_url"),
                            rs.getInt("files_changed"),
                            rs.getInt("lines_changed"),
                            rs.getInt("priority"),
                            rs.getString("jira_key")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to search jobs: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Returns true when an active (PENDING, QUEUED, or RUNNING) review job exists
     * for the given PR ID scoped to a specific workspace and repository.
     * Scoping prevents cross-repo false positives when two repos share the same
     * numeric PR ID (e.g. PR #1 in org/foo vs PR #1 in org/bar).
     */
    public boolean hasActiveReviewJobForPr(String prId, String workspace, String repoSlug) {
        String sql = """
                SELECT 1 FROM jobs
                WHERE job_type = 'REVIEW'
                  AND status IN ('PENDING','QUEUED','RUNNING')
                  AND pr_id = ?
                  AND workspace = ?
                  AND repo_slug = ?
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prId);
            ps.setString(2, workspace);
            ps.setString(3, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.errorf("hasActiveReviewJobForPr query failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Returns all REVIEW jobs that are currently active (PENDING, QUEUED, or RUNNING).
     * Used during boot reconciliation to cancel jobs whose PRs were already merged.
     */
    public List<JobRecord> findActiveReviewJobs() {
        String sql = """
                SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed,
                       pr_author, workspace, repo_slug, priority, coverage_data,
                       aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                       promotion_job_id, workspace_path
                FROM jobs
                WHERE job_type = 'REVIEW'
                  AND status IN ('PENDING','QUEUED','RUNNING')
                ORDER BY created_at ASC
                """;
        List<JobRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("findActiveReviewJobs failed: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Returns true when an active (PENDING, QUEUED, or RUNNING) review job exists
     * for the given Jira issue key. Used to prevent duplicate review jobs.
     */
    public boolean hasActiveReviewJob(String issueKey) {
        String sql = """
                SELECT 1 FROM jobs
                WHERE job_type IN ('REVIEW_EPIC','REVIEW_FEATURE','REVIEW_USERSTORY')
                  AND status IN ('PENDING','QUEUED','RUNNING')
                  AND request_payload->>'issueKey' = ?
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, issueKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.warnf("Failed to check active review job for %s: %s", issueKey, e.getMessage());
            return false;
        }
    }

    /**
     * Returns up to {@code limit} QUEUED roadmap review jobs ordered by priority DESC,
     * created_at ASC, excluding any job IDs already in the in-memory dispatch queue.
     * Used by the refill scheduler in JobQueue.
     */
    public List<JobRecord> findQueuedReviewJobs(Set<String> excludeJobIds, int limit) {
        String sql = """
                SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed,
                       pr_author, workspace, repo_slug, priority, coverage_data,
                       aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                       promotion_job_id, workspace_path
                FROM jobs
                WHERE job_type IN ('REVIEW_EPIC','REVIEW_FEATURE','REVIEW_USERSTORY')
                  AND status = 'QUEUED'
                ORDER BY priority DESC, created_at ASC
                LIMIT ?
                """;
        List<JobRecord> results = new ArrayList<>();
        long safeLimitLong = (long) limit + excludeJobIds.size();
        int safeLimit = safeLimitLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) safeLimitLong;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, safeLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && results.size() < limit) {
                    String jobId = rs.getString("job_id");
                    if (excludeJobIds.contains(jobId)) continue;
                    JobRecord job = mapRow(rs);
                    if (job != null) {
                        results.add(job);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query queued review jobs: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Counts QUEUED or RUNNING roadmap review jobs associated with the given roadmap ID.
     * Used by the /roadmap/{id}/active-review-count endpoint.
     */
    public long countActiveReviewJobsForRoadmap(String roadmapId) {
        String sql = """
                SELECT COUNT(*) FROM jobs
                WHERE job_type IN ('REVIEW_EPIC','REVIEW_FEATURE','REVIEW_USERSTORY')
                  AND status IN ('PENDING','QUEUED','RUNNING')
                  AND request_payload->>'roadmapId' = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roadmapId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to count active review jobs for roadmap %s: %s", roadmapId, e.getMessage());
        }
        return 0;
    }

    /**
     * Returns all jobs associated with a given pull request ID scoped to a specific
     * workspace and repository, ordered by {@code created_at DESC}.
     * Searches both active and history tables.
     * Use this overload from webhook handlers to avoid cross-repo collisions.
     */
    public List<JobRecord> findByPrId(String prId, String workspace, String repoSlug) {
        if (prId == null || prId.isBlank()) return List.of();
        String sql = """
                SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed,
                       pr_author, workspace, repo_slug, priority, coverage_data,
                       aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                       promotion_job_id, workspace_path
                FROM jobs WHERE pr_id = ? AND workspace = ? AND repo_slug = ?
                UNION ALL
                SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed,
                       pr_author, workspace, repo_slug, priority, coverage_data,
                       aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                       promotion_job_id, workspace_path
                FROM job_history WHERE pr_id = ? AND workspace = ? AND repo_slug = ?
                ORDER BY created_at DESC
                """;
        List<JobRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prId);
            ps.setString(2, workspace);
            ps.setString(3, repoSlug);
            ps.setString(4, prId);
            ps.setString(5, workspace);
            ps.setString(6, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JobRecord job = mapRow(rs);
                    if (job != null) results.add(job);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("findByPrId(%s, %s, %s) failed: %s", prId, workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    /**
     * Returns all jobs associated with a given pull request ID, ordered by
     * {@code created_at DESC}. Searches both active and history tables.
     *
     * <p>Falls back to a {@code LIKE} match on {@code pr_url} when {@code prId}
     * is a numeric string, to handle jobs where only {@code prUrl} was stored.
     *
     * @deprecated Prefer {@link #findByPrId(String, String, String)} to avoid cross-repo collisions.
     */
    @Deprecated
    public List<JobRecord> findByPrId(String prId) {
        if (prId == null || prId.isBlank()) return List.of();

        String sqlExact = """
                SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed,
                       pr_author, workspace, repo_slug, priority, coverage_data,
                       aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                       promotion_job_id, workspace_path
                FROM jobs WHERE pr_id = ?
                UNION ALL
                SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed,
                       pr_author, workspace, repo_slug, priority, coverage_data,
                       aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                       promotion_job_id, workspace_path
                FROM job_history WHERE pr_id = ?
                ORDER BY created_at DESC
                """;
        List<JobRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlExact)) {
            ps.setString(1, prId);
            ps.setString(2, prId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JobRecord job = mapRow(rs);
                    if (job != null) results.add(job);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("findByPrId(%s) failed: %s", prId, e.getMessage());
        }

        // Fallback: pr_url LIKE match (for older jobs where pr_id wasn't stored)
        if (results.isEmpty()) {
            String sqlLike = """
                    SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                           summary, error_message, pr_url, pr_id, files_changed, lines_changed,
                           pr_author, workspace, repo_slug, priority, coverage_data,
                           aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                           promotion_job_id, workspace_path
                    FROM jobs WHERE pr_url LIKE ?
                    UNION ALL
                    SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                           summary, error_message, pr_url, pr_id, files_changed, lines_changed,
                           pr_author, workspace, repo_slug, priority, coverage_data,
                           aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                           promotion_job_id, workspace_path
                    FROM job_history WHERE pr_url LIKE ?
                    ORDER BY created_at DESC
                    """;
            String pattern = "%/" + prId;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sqlLike)) {
                ps.setString(1, pattern);
                ps.setString(2, pattern);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JobRecord job = mapRow(rs);
                        if (job != null) results.add(job);
                    }
                }
            } catch (SQLException e) {
                LOG.errorf("findByPrId fallback LIKE(%s) failed: %s", prId, e.getMessage());
            }
        }

        return results;
    }

    /**
     * Returns all jobs (active + history) that have a non-null {@code jira_issue_type},
     * ordered newest first. The caller is responsible for filtering to the specific
     * bug types configured for SOC II compliance.
     */
    public List<JobRecord> findJobsWithJiraIssueType(int limit) {
        int safeLimit = Math.min(Math.max(1, limit), 500);
        String sql = """
                SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed,
                       pr_author, workspace, repo_slug, priority, coverage_data,
                       aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                       promotion_job_id, workspace_path
                FROM jobs
                WHERE jira_key IS NOT NULL
                UNION ALL
                SELECT job_id, job_type, status, request_payload, created_at, updated_at,
                       summary, error_message, pr_url, pr_id, files_changed, lines_changed,
                       pr_author, workspace, repo_slug, priority, coverage_data,
                       aikido_issue_id, fix_branch_name, jira_issue_type, jira_priority, jira_created_at,
                       promotion_job_id, workspace_path
                FROM job_history
                WHERE jira_key IS NOT NULL
                ORDER BY created_at DESC
                LIMIT ?
                """;
        List<JobRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, safeLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JobRecord job = mapRow(rs);
                    if (job != null) results.add(job);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("findJobsWithJiraIssueType failed: %s", e.getMessage());
        }
        return results;
    }

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

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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
                case CHAT -> null;
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
            case GENERATE_TESTS -> job.getGenerateTestsRequest();
            case GENERATE_DOCS -> job.getGenerateDocsRequest();
            case SYNC_CONFLUENCE -> job.getSyncConfluenceRequest();
            case METRICS -> job.getMetricsRequest();
            case QUALITY_REPORT -> job.getQualityReportRequest();
            case REVIEW_EPIC, REVIEW_FEATURE, REVIEW_USERSTORY -> job.getJiraReviewRequest();
            case PROMOTE -> job.getPromoteRequest();
            case SELF_ANALYSIS -> job.getPayload() instanceof SelfAnalysisRequest r ? r : null;
            case GENERATE_ARCHITECTURE ->
                    job.getPayload() instanceof GenerateArchitectureRequest r ? r : null;
            case GENERATE_CLOUD_ARCHITECTURE ->
                    job.getPayload() instanceof GenerateCloudArchitectureRequest r ? r : null;
            case KNOWLEDGE_GRAPH ->
                    job.getPayload() instanceof KnowledgeGraphRequest r ? r : null;
            case TECH_DEBT ->
                    job.getPayload() instanceof TechDebtRequest r ? r : null;
            case CHAT -> null;
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
        if (job.getJiraReviewRequest() != null) return job.getJiraReviewRequest().issueKey();
        if (job.getPromoteRequest() != null) return job.getPromoteRequest().jiraKey();
        return null;
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
