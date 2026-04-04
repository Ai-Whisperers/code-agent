-- Track the Jira ticket created from a log analysis finding so the UI can
-- link directly to it and avoid creating duplicates.

ALTER TABLE log_analysis_findings
    ADD COLUMN jira_key TEXT;
