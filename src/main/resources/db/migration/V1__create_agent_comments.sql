CREATE TABLE agent_comments (
    comment_id    BIGINT PRIMARY KEY,
    pr_id         TEXT NOT NULL,
    workspace     TEXT NOT NULL,
    repo_slug     TEXT NOT NULL,
    file_path     TEXT,
    line_number   INTEGER,
    category      TEXT,
    severity      TEXT,
    finding_text  TEXT NOT NULL,
    review_job_id TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_comments_pr ON agent_comments(workspace, repo_slug, pr_id);
