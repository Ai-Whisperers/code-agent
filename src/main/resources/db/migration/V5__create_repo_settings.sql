CREATE TABLE repo_settings (
    id              BIGSERIAL PRIMARY KEY,
    workspace       TEXT NOT NULL,
    repo_slug       TEXT NOT NULL,
    review_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    rule_names      TEXT,
    review_prompt   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(workspace, repo_slug)
);

CREATE INDEX idx_repo_settings_workspace ON repo_settings(workspace);
