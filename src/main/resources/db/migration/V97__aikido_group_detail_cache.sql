-- Cache for Aikido issue group detail responses.
-- Populated lazily on first fetch and kept fresh via webhook upserts.
-- Avoids repeated detail API calls (20 req/min rate limit) during snapshot rebuilds.
CREATE TABLE IF NOT EXISTS aikido_group_detail_cache (
    group_id          INTEGER     PRIMARY KEY,
    issue_type        TEXT,
    title             TEXT,
    description       TEXT,
    severity          TEXT,
    severity_score    INTEGER,
    package_name      TEXT,
    current_version   TEXT,
    fixed_version     TEXT,
    cve_id            TEXT,
    cve_description   TEXT,
    cvss_score        NUMERIC(5,2),
    repo_name         TEXT,
    repo_url          TEXT,
    container_image   TEXT,
    how_to_fix        TEXT,
    related_cve_ids   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    group_status      TEXT,
    time_to_fix_minutes INTEGER,
    fetched_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agdc_severity    ON aikido_group_detail_cache (severity);
CREATE INDEX IF NOT EXISTS idx_agdc_updated_at  ON aikido_group_detail_cache (updated_at DESC);
