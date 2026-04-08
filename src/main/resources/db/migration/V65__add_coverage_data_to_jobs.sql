-- Stores before/after coverage snapshots captured during GENERATE_TESTS jobs.
ALTER TABLE jobs        ADD COLUMN IF NOT EXISTS coverage_data JSONB;
ALTER TABLE job_history ADD COLUMN IF NOT EXISTS coverage_data JSONB;
