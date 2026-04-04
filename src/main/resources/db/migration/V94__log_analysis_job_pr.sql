-- Add job tracking fields to log_analysis_findings
ALTER TABLE log_analysis_findings
    ADD COLUMN IF NOT EXISTS job_id  TEXT,
    ADD COLUMN IF NOT EXISTS pr_url  TEXT;
