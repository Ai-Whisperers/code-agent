ALTER TABLE jobs         ADD COLUMN IF NOT EXISTS parent_job_id VARCHAR;
ALTER TABLE jobs         ADD COLUMN IF NOT EXISTS depth         INTEGER NOT NULL DEFAULT 0;

ALTER TABLE job_history  ADD COLUMN IF NOT EXISTS parent_job_id VARCHAR;
ALTER TABLE job_history  ADD COLUMN IF NOT EXISTS depth         INTEGER NOT NULL DEFAULT 0;

ALTER TABLE ai_calls     ADD COLUMN IF NOT EXISTS parent_job_id VARCHAR;
ALTER TABLE ai_calls     ADD COLUMN IF NOT EXISTS depth         INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_jobs_parent_job_id     ON jobs(parent_job_id);
CREATE INDEX IF NOT EXISTS idx_ai_calls_parent_job_id ON ai_calls(parent_job_id);
