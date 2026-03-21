CREATE TABLE execution_plans (
    plan_id       TEXT PRIMARY KEY,
    status        TEXT NOT NULL DEFAULT 'DRAFT',
    source_type   TEXT NOT NULL,
    source_ref    TEXT,
    repo_url      TEXT NOT NULL,
    target_branch TEXT NOT NULL DEFAULT 'main',
    title         TEXT,
    plan_data     JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at   TIMESTAMPTZ,
    summary       TEXT,
    error_message TEXT
);

CREATE INDEX idx_plans_status ON execution_plans(status);
CREATE INDEX idx_plans_created_at ON execution_plans(created_at);
