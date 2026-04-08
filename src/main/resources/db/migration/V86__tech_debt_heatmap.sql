-- Technical Debt Heatmap: snapshots and per-file composite debt scores.
--
-- One snapshot is created per run (manual trigger or scheduler).
-- Retention: the handler deletes snapshots older than 90 days after each
-- successful run; child tables cascade-delete automatically.
--
-- Debt score signals per file:
--   complexity_score  (30%) — methodsAboveThreshold / totalMethods from QualityReport
--   coverage_gap      (25%) — 1 - lineRate from QualityReport CoverageSection
--   churn_score       (25%) — normalised (linesAdded + linesDeleted) from knowledge_scores
--   staleness_score   (20%) — daysSinceLastCommit / 365, capped at 1.0

CREATE TABLE tech_debt_snapshots (
    id              BIGSERIAL    PRIMARY KEY,
    product_id      TEXT,                          -- NULL = all products
    computed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lookback_days   INT          NOT NULL DEFAULT 365,
    total_files     INT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE tech_debt_snapshots IS
    'One row per tech-debt computation run. Child rows cascade on delete.';

CREATE INDEX tech_debt_snapshots_computed_at_idx
    ON tech_debt_snapshots (computed_at DESC);

-- ── Per-file debt scores ──────────────────────────────────────────────────────

CREATE TABLE tech_debt_files (
    id                BIGSERIAL     PRIMARY KEY,
    snapshot_id       BIGINT        NOT NULL REFERENCES tech_debt_snapshots(id) ON DELETE CASCADE,
    repo_slug         TEXT          NOT NULL,
    file_path         TEXT          NOT NULL,
    -- individual signal scores in [0.0, 1.0]; higher = more debt
    complexity_score  NUMERIC(5,4)  NOT NULL DEFAULT 0,
    coverage_gap      NUMERIC(5,4)  NOT NULL DEFAULT 0,
    churn_score       NUMERIC(5,4)  NOT NULL DEFAULT 0,
    staleness_score   NUMERIC(5,4)  NOT NULL DEFAULT 0,
    -- weighted composite: 0.30*complexity + 0.25*coverage_gap + 0.25*churn + 0.20*staleness
    debt_score        NUMERIC(5,4)  NOT NULL DEFAULT 0,
    last_commit_at    DATE
);

COMMENT ON TABLE tech_debt_files IS
    'One row per (snapshot, repo, file) with individual signal scores and composite debt_score.';

CREATE INDEX tech_debt_files_snapshot_repo_idx
    ON tech_debt_files (snapshot_id, repo_slug);

CREATE INDEX tech_debt_files_snapshot_debt_idx
    ON tech_debt_files (snapshot_id, debt_score DESC);
