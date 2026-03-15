CREATE TABLE IF NOT EXISTS code_metrics_snapshots (
    snapshot_id   TEXT        PRIMARY KEY,
    plan_id       TEXT,
    workspace     TEXT        NOT NULL,
    repo_slug     TEXT        NOT NULL,
    branch        TEXT        NOT NULL,
    threshold     INT         NOT NULL DEFAULT 10,
    snapshot_data JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_metrics_repo
    ON code_metrics_snapshots (workspace, repo_slug);

CREATE INDEX IF NOT EXISTS idx_metrics_plan
    ON code_metrics_snapshots (plan_id)
    WHERE plan_id IS NOT NULL;
