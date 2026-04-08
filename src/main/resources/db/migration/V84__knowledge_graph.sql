-- Knowledge Graph: snapshots and per-author per-file scores.
--
-- One snapshot is created per run (weekly scheduler or manual trigger).
-- Retention: the handler deletes snapshots older than 90 days after each
-- successful run; child tables cascade-delete automatically.

CREATE TABLE knowledge_snapshots (
    id              BIGSERIAL    PRIMARY KEY,
    product_id      TEXT,                          -- NULL = all products
    computed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lookback_days   INT          NOT NULL DEFAULT 365,
    total_repos     INT          NOT NULL DEFAULT 0,
    total_authors   INT          NOT NULL DEFAULT 0,
    total_files     INT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE knowledge_snapshots IS
    'One row per knowledge-graph computation run. Child rows cascade on delete.';

CREATE INDEX knowledge_snapshots_computed_at_idx
    ON knowledge_snapshots (computed_at DESC);

-- ── Per-author per-file scores ────────────────────────────────────────────────

CREATE TABLE knowledge_scores (
    id              BIGSERIAL    PRIMARY KEY,
    snapshot_id     BIGINT       NOT NULL REFERENCES knowledge_snapshots(id) ON DELETE CASCADE,
    author_email    TEXT         NOT NULL,
    author_name     TEXT,
    repo_slug       TEXT         NOT NULL,
    file_path       TEXT         NOT NULL,
    -- raw signals
    commit_count    INT          NOT NULL DEFAULT 0,
    lines_added     INT          NOT NULL DEFAULT 0,
    lines_deleted   INT          NOT NULL DEFAULT 0,
    blame_lines     INT          NOT NULL DEFAULT 0,  -- lines owned in current HEAD
    total_lines     INT          NOT NULL DEFAULT 0,  -- total lines in file at HEAD
    last_commit_at  DATE,
    -- computed
    score           NUMERIC(12,4) NOT NULL DEFAULT 0,
    service_score   NUMERIC(12,4) NOT NULL DEFAULT 0  -- SUM of file scores per (author, repo)
);

COMMENT ON TABLE knowledge_scores IS
    'One row per (snapshot, author, repo, file). service_score is denormalised for fast aggregation.';

CREATE INDEX knowledge_scores_snapshot_author_idx
    ON knowledge_scores (snapshot_id, author_email);

CREATE INDEX knowledge_scores_snapshot_repo_file_idx
    ON knowledge_scores (snapshot_id, repo_slug, file_path);
