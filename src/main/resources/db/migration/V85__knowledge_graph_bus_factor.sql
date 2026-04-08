-- Knowledge Graph: bus-factor flags per file per snapshot.
-- Populated by the handler after all scores are written.

CREATE TABLE knowledge_bus_factor (
    id                  BIGSERIAL    PRIMARY KEY,
    snapshot_id         BIGINT       NOT NULL REFERENCES knowledge_snapshots(id) ON DELETE CASCADE,
    repo_slug           TEXT         NOT NULL,
    file_path           TEXT         NOT NULL,
    top_author_email    TEXT         NOT NULL,
    top_author_name     TEXT,
    top_score           NUMERIC(12,4) NOT NULL DEFAULT 0,
    top_ownership_pct   NUMERIC(5,2)  NOT NULL DEFAULT 0,  -- % of blame lines
    second_author_email TEXT,
    second_score        NUMERIC(12,4) NOT NULL DEFAULT 0,
    bus_factor_flag     BOOLEAN      NOT NULL DEFAULT false,
    -- critical = top_ownership_pct > 80, warning = 60-80
    risk_level          TEXT         NOT NULL DEFAULT 'none'  -- 'none' | 'warning' | 'critical'
);

COMMENT ON TABLE knowledge_bus_factor IS
    'One row per (snapshot, repo, file) where bus factor was evaluated. '
    'bus_factor_flag = true when top author owns > 60% of blame lines.';

CREATE INDEX knowledge_bus_factor_snapshot_idx
    ON knowledge_bus_factor (snapshot_id);

CREATE INDEX knowledge_bus_factor_flagged_idx
    ON knowledge_bus_factor (snapshot_id, bus_factor_flag)
    WHERE bus_factor_flag = true;
