ALTER TABLE jobs        ADD COLUMN IF NOT EXISTS pr_author  TEXT;
ALTER TABLE jobs        ADD COLUMN IF NOT EXISTS workspace  TEXT;
ALTER TABLE jobs        ADD COLUMN IF NOT EXISTS repo_slug  TEXT;

ALTER TABLE job_history ADD COLUMN IF NOT EXISTS pr_author  TEXT;
ALTER TABLE job_history ADD COLUMN IF NOT EXISTS workspace  TEXT;
ALTER TABLE job_history ADD COLUMN IF NOT EXISTS repo_slug  TEXT;

CREATE INDEX IF NOT EXISTS idx_jobs_workspace_repo
    ON jobs(workspace, repo_slug);

CREATE INDEX IF NOT EXISTS idx_job_history_workspace_repo
    ON job_history(workspace, repo_slug);

CREATE INDEX IF NOT EXISTS idx_jobs_pr_author
    ON jobs(pr_author) WHERE pr_author IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_job_history_pr_author
    ON job_history(pr_author) WHERE pr_author IS NOT NULL;
