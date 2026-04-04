package com.eneve.agent.agent.store;

import com.eneve.agent.model.*;
import io.agroal.api.AgroalDataSource;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only query methods extracted from {@link JobStore}.
 *
 * <p>All methods here are pure queries — they never write to the database or
 * touch the in-memory cache. {@link JobStore} holds the single instance and
 * delegates to it, keeping the CRUD / cache logic in one place and the
 * query logic here.
 */
class JobQueryHelper {

    private static final Logger LOG = Logger.getLogger(JobQueryHelper.class);

    private final AgroalDataSource dataSource;
    private final JobRowMapper rowMapper;

    JobQueryHelper(AgroalDataSource dataSource, JobRowMapper rowMapper) {
        this.dataSource = dataSource;
        this.rowMapper = rowMapper;
    }

    // ── Existence / boolean queries ───────────────────────────────────────────

    boolean hasActiveJobForAikidoGroupId(String groupId) {
        String sql = """
                SELECT 1 FROM jobs
                WHERE aikido_issue_id = ?
                  AND status IN ('PENDING','QUEUED','RUNNING','AWAITING_APPROVAL')
                LIMIT 1
                """;
        return existsQuery(sql, groupId);
    }

    boolean hasActiveSelfAnalysisForJob(String failedJobId) {
        String sql = """
                SELECT 1 FROM jobs
                WHERE job_type = 'SELF_ANALYSIS'
                  AND status IN ('PENDING','QUEUED','RUNNING')
                  AND request_payload->>'failedJobId' = ?
                LIMIT 1
                """;
        return existsQuery(sql, failedJobId);
    }

    boolean hasRecentSuccessfulSelfAnalysisForJob(String failedJobId, int cooldownHours) {
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

    boolean hasActiveJobForJiraKey(String jiraKey) {
        String sql = """
                SELECT 1 FROM jobs
                WHERE jira_key = ?
                  AND status IN ('PENDING','QUEUED','RUNNING','AWAITING_APPROVAL')
                LIMIT 1
                """;
        return existsQuery(sql, jiraKey);
    }

    boolean hasEverBeenProcessed(String jiraKey) {
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

    boolean hasActiveReviewJobForPr(String prId, String workspace, String repoSlug) {
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

    boolean hasActiveReviewJob(String issueKey) {
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

    // ── Record-returning queries ───────────────────────────────────────────────

    List<JobRecord> findByStatus(JobStatus status) {
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
                    JobRecord job = rowMapper.mapRow(rs);
                    if (job != null) results.add(job);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query jobs by status %s: %s", status, e.getMessage());
        }
        return results;
    }

    JobRecord findLatestJobForAikidoGroupId(String groupId, String repoSlug) {
        if (repoSlug != null && !repoSlug.isBlank()) {
            JobRecord scoped = findLatestJobForAikidoGroupAndRepo(groupId, repoSlug);
            if (scoped != null) return scoped;
        }
        return findLatestJobForAikidoGroupAndRepo(groupId, null);
    }

    /** @deprecated Use {@link #findLatestJobForAikidoGroupId(String, String)} with a repoSlug. */
    @Deprecated
    JobRecord findLatestJobForAikidoGroupId(String groupId) {
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
                if (rs.next()) return rowMapper.mapRow(rs);
            }
        } catch (SQLException e) {
            LOG.errorf("findLatestJobForAikidoGroupAndRepo(%s, %s) failed: %s", groupId, repoSlug, e.getMessage());
        }
        return null;
    }

    String findPreservedWorkspacePath(String branchName) {
        if (branchName == null || branchName.isBlank()) return null;
        String historySql = """
                SELECT workspace_path FROM job_history
                WHERE fix_branch_name = ?
                  AND status = 'FAILED'
                  AND workspace_path IS NOT NULL
                ORDER BY archived_at DESC
                LIMIT 1
                """;
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

    Set<String> getProcessedKeys() {
        String sql = """
                SELECT DISTINCT jira_key FROM jobs         WHERE jira_key IS NOT NULL
                UNION
                SELECT DISTINCT jira_key FROM job_history  WHERE jira_key IS NOT NULL
                """;
        Set<String> keys = ConcurrentHashMap.newKeySet();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) keys.add(rs.getString("jira_key"));
        } catch (SQLException e) {
            LOG.errorf("Failed to query processed keys: %s", e.getMessage());
        }
        return keys;
    }

    List<JobStatusResponse> search(JobStatus status, JobType jobType, int limit, int offset) {
        int safeLimit  = Math.min(Math.max(1, limit), 200);
        int safeOffset = Math.max(0, offset);

        StringBuilder where = new StringBuilder();
        List<String> params = new ArrayList<>();
        if (status != null)  { where.append(" AND status = ?");   params.add(status.name()); }
        if (jobType != null) { where.append(" AND job_type = ?"); params.add(jobType.name()); }

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
            for (String p : params) ps.setString(idx++, p);
            for (String p : params) ps.setString(idx++, p);
            ps.setInt(idx++, safeLimit);
            ps.setInt(idx, safeOffset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JobType type;
                    try { type = JobType.valueOf(rs.getString("job_type")); }
                    catch (IllegalArgumentException e) { continue; }
                    JobStatus jobStatus;
                    try { jobStatus = JobStatus.valueOf(rs.getString("status")); }
                    catch (IllegalArgumentException e) { jobStatus = JobStatus.FAILED; }
                    results.add(JobStatusResponse.fromSearch(
                            rs.getString("job_id"), type, jobStatus,
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("summary"), rs.getString("error_message"),
                            rs.getString("pr_url"), rs.getInt("files_changed"),
                            rs.getInt("lines_changed"), rs.getInt("priority"),
                            rs.getString("jira_key")));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to search jobs: %s", e.getMessage());
        }
        return results;
    }

    List<JobRecord> findActiveReviewJobs() {
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
            while (rs.next()) results.add(rowMapper.mapRow(rs));
        } catch (SQLException e) {
            LOG.errorf("findActiveReviewJobs failed: %s", e.getMessage());
        }
        return results;
    }

    List<JobRecord> findQueuedReviewJobs(Set<String> excludeJobIds, int limit) {
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
                    JobRecord job = rowMapper.mapRow(rs);
                    if (job != null) results.add(job);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query queued review jobs: %s", e.getMessage());
        }
        return results;
    }

    long countActiveReviewJobsForRoadmap(String roadmapId) {
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

    List<JobRecord> findByPrId(String prId, String workspace, String repoSlug) {
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
            ps.setString(1, prId); ps.setString(2, workspace); ps.setString(3, repoSlug);
            ps.setString(4, prId); ps.setString(5, workspace); ps.setString(6, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JobRecord job = rowMapper.mapRow(rs);
                    if (job != null) results.add(job);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("findByPrId(%s, %s, %s) failed: %s", prId, workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    /**
     * @deprecated Prefer {@link #findByPrId(String, String, String)} to avoid cross-repo collisions.
     */
    @Deprecated
    List<JobRecord> findByPrId(String prId) {
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
            ps.setString(1, prId); ps.setString(2, prId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JobRecord job = rowMapper.mapRow(rs);
                    if (job != null) results.add(job);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("findByPrId(%s) failed: %s", prId, e.getMessage());
        }
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
                ps.setString(1, pattern); ps.setString(2, pattern);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JobRecord job = rowMapper.mapRow(rs);
                        if (job != null) results.add(job);
                    }
                }
            } catch (SQLException e) {
                LOG.errorf("findByPrId fallback LIKE(%s) failed: %s", prId, e.getMessage());
            }
        }
        return results;
    }

    List<JobRecord> findJobsWithJiraIssueType(int limit) {
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
                    JobRecord job = rowMapper.mapRow(rs);
                    if (job != null) results.add(job);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("findJobsWithJiraIssueType failed: %s", e.getMessage());
        }
        return results;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
}
