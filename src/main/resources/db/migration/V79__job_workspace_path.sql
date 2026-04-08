-- Stores the temp directory path of the workspace used by a job.
-- When a job fails, the workspace is preserved on disk and this path is recorded
-- so a subsequent retry job can reuse the already-cloned repository instead of
-- cloning from scratch.
ALTER TABLE jobs        ADD COLUMN IF NOT EXISTS workspace_path TEXT;
ALTER TABLE job_history ADD COLUMN IF NOT EXISTS workspace_path TEXT;
