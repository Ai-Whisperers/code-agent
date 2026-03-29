-- Add Aikido remediation tracking and SLA fields to jobs and job_history.
-- aikido_issue_id  : links a job to an Aikido vulnerability group (enables deduplication).
-- fix_branch_name  : the original agent fix branch, stored for cherry-pick promotion to main.
-- jira_issue_type  : cached at submission for SOC2 / SLA calculations.
-- jira_priority    : cached at submission for SLA deadline calculation.
-- jira_created_at  : cached at submission; SLA clock starts here.
-- promotion_job_id : links the develop-merge job to its PROMOTE job (develop→main cherry-pick).

ALTER TABLE jobs
    ADD COLUMN IF NOT EXISTS aikido_issue_id  VARCHAR,
    ADD COLUMN IF NOT EXISTS fix_branch_name  VARCHAR,
    ADD COLUMN IF NOT EXISTS jira_issue_type  VARCHAR,
    ADD COLUMN IF NOT EXISTS jira_priority    VARCHAR,
    ADD COLUMN IF NOT EXISTS jira_created_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS promotion_job_id VARCHAR;

ALTER TABLE job_history
    ADD COLUMN IF NOT EXISTS aikido_issue_id  VARCHAR,
    ADD COLUMN IF NOT EXISTS fix_branch_name  VARCHAR,
    ADD COLUMN IF NOT EXISTS jira_issue_type  VARCHAR,
    ADD COLUMN IF NOT EXISTS jira_priority    VARCHAR,
    ADD COLUMN IF NOT EXISTS jira_created_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS promotion_job_id VARCHAR;

CREATE INDEX IF NOT EXISTS idx_jobs_aikido_issue_id
    ON jobs (aikido_issue_id)
    WHERE aikido_issue_id IS NOT NULL;
