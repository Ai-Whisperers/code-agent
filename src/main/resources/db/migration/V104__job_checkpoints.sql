CREATE TABLE job_checkpoints (
    job_id          VARCHAR PRIMARY KEY,
    iteration       INTEGER     NOT NULL,
    messages_json   JSONB       NOT NULL,
    git_commit_sha  VARCHAR     NOT NULL,
    checkpointed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
