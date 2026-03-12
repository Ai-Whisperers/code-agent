CREATE TABLE review_memory (
    id                BIGSERIAL PRIMARY KEY,
    workspace         TEXT NOT NULL,
    repo_slug         TEXT NOT NULL,
    memory_text       TEXT NOT NULL,
    category          TEXT,
    source            TEXT NOT NULL,
    source_comment_id BIGINT,
    source_pr_id      TEXT,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        TEXT
);

CREATE INDEX idx_review_memory_repo ON review_memory(workspace, repo_slug, is_active);
