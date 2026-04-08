-- Adds monitoring lifecycle support to log_analysis_findings.
-- status values: OPEN | DISMISSED | MONITORING | CLOSED
ALTER TABLE log_analysis_findings
    ADD COLUMN monitoring_since TIMESTAMPTZ;
