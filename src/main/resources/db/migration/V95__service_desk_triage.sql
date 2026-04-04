CREATE TABLE IF NOT EXISTS service_desk_triage_findings (
    id                BIGSERIAL PRIMARY KEY,
    issue_key         TEXT        NOT NULL,
    project_key       TEXT        NOT NULL DEFAULT '',
    category          TEXT        NOT NULL,  -- QUESTION | REQUEST | BUG_REPORT | OUTAGE_REPORT
    severity          TEXT,                  -- high | medium | low | null for non-bug/outage
    confidence        NUMERIC(4,2),          -- 0.00–1.00
    triage_reason     TEXT,
    deep_analysis     TEXT,
    similar_issue_keys JSONB      NOT NULL DEFAULT '[]'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_desk_triage_issue_key UNIQUE (issue_key)
);

CREATE INDEX IF NOT EXISTS idx_sdt_project_key  ON service_desk_triage_findings (project_key);
CREATE INDEX IF NOT EXISTS idx_sdt_category      ON service_desk_triage_findings (category);
CREATE INDEX IF NOT EXISTS idx_sdt_created_at    ON service_desk_triage_findings (created_at DESC);
