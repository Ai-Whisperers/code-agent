-- Adds a SOC II flag to the open_pull_requests cache.
-- When true, the linked agent job is SOC II–applicable (its Jira issue type
-- matches the configured soc2.bug-issue-types setting).
-- Defaults to false; populated by PullRequestsResource when enriching cache entries.

ALTER TABLE open_pull_requests
    ADD COLUMN IF NOT EXISTS soc2 BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_open_prs_soc2
    ON open_pull_requests (soc2) WHERE soc2 = true;
