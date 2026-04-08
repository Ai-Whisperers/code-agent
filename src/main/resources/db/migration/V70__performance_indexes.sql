-- Composite indexes for JobStore.search() UNION ALL query
-- Replaces sequential scans when filtering by status/job_type ordered by created_at DESC

CREATE INDEX IF NOT EXISTS idx_jobs_status_created
    ON jobs(status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_jobs_job_type_created
    ON jobs(job_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_job_history_status_created
    ON job_history(status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_job_history_job_type_created
    ON job_history(job_type, created_at DESC);

-- Index for pr_id lookups across both tables (used by findByPrId)
CREATE INDEX IF NOT EXISTS idx_jobs_pr_id
    ON jobs(pr_id) WHERE pr_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_job_history_pr_id
    ON job_history(pr_id) WHERE pr_id IS NOT NULL;

-- JSONB expression indexes for hasActiveReviewJob and countActiveReviewJobsForRoadmap
CREATE INDEX IF NOT EXISTS idx_jobs_payload_issue_key
    ON jobs ((request_payload->>'issueKey'))
    WHERE job_type IN ('REVIEW_EPIC', 'REVIEW_FEATURE', 'REVIEW_USERSTORY');

CREATE INDEX IF NOT EXISTS idx_jobs_payload_roadmap_id
    ON jobs ((request_payload->>'roadmapId'))
    WHERE job_type IN ('REVIEW_EPIC', 'REVIEW_FEATURE', 'REVIEW_USERSTORY');
