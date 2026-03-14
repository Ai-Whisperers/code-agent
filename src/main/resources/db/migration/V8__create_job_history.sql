CREATE TABLE job_history (
    job_id          TEXT PRIMARY KEY,
    job_type        TEXT NOT NULL,
    status          TEXT NOT NULL,
    request_payload JSONB,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    archived_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    summary         TEXT,
    error_message   TEXT,
    pr_url          TEXT,
    pr_id           TEXT,
    files_changed   INTEGER NOT NULL DEFAULT 0,
    lines_changed   INTEGER NOT NULL DEFAULT 0,
    jira_key        TEXT
);

CREATE INDEX idx_job_history_status      ON job_history(status);
CREATE INDEX idx_job_history_jira_key    ON job_history(jira_key);
CREATE INDEX idx_job_history_created_at  ON job_history(created_at);
CREATE INDEX idx_job_history_archived_at ON job_history(archived_at);
