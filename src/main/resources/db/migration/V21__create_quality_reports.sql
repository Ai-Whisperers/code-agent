CREATE TABLE IF NOT EXISTS quality_reports (
    report_id   TEXT        PRIMARY KEY,
    workspace   TEXT        NOT NULL,
    repo_slug   TEXT        NOT NULL,
    branch      TEXT        NOT NULL,
    report_data JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_quality_reports_repo
    ON quality_reports (workspace, repo_slug);

CREATE INDEX IF NOT EXISTS idx_quality_reports_branch
    ON quality_reports (workspace, repo_slug, branch, created_at DESC);
