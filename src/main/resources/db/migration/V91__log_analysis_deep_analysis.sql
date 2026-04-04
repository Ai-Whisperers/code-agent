-- Add deep-analysis fields to log_analysis_findings.
-- deep_analysis  : full Claude Sonnet analysis text (markdown)
-- analysed_at    : timestamp when the deep analysis was last run

ALTER TABLE log_analysis_findings
    ADD COLUMN deep_analysis TEXT,
    ADD COLUMN analysed_at   TIMESTAMPTZ;
