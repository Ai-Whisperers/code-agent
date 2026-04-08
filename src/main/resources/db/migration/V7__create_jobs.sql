CREATE TABLE jobs (
    job_id          TEXT PRIMARY KEY,
    job_type        TEXT NOT NULL,
    status          TEXT NOT NULL,
    request_payload JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    summary         TEXT,
    error_message   TEXT,
    pr_url          TEXT,
    pr_id           TEXT,
    files_changed   INTEGER NOT NULL DEFAULT 0,
    lines_changed   INTEGER NOT NULL DEFAULT 0,
    jira_key        TEXT
);

CREATE INDEX idx_jobs_status ON jobs(status);
CREATE INDEX idx_jobs_jira_key ON jobs(jira_key);
CREATE INDEX idx_jobs_created_at ON jobs(created_at);
