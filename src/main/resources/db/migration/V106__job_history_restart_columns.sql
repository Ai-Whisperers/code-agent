ALTER TABLE job_history ADD COLUMN IF NOT EXISTS restart_from_job_id  VARCHAR;
ALTER TABLE job_history ADD COLUMN IF NOT EXISTS restart_iteration     INTEGER;
ALTER TABLE job_history ADD COLUMN IF NOT EXISTS additional_iterations INTEGER NOT NULL DEFAULT 0;
