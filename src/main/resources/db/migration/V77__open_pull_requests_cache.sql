-- Cache of open (and recently closed) pull requests fetched from the SCM.
-- Populated on startup by PrCacheSyncService and kept fresh by Bitbucket webhooks.
--
-- status       mirrors Bitbucket states: OPEN, MERGED, DECLINED, SUPERSEDED
-- cached_at    timestamp of the last upsert — used for TTL staleness checks
-- The GIN index on the concatenated text columns enables fast free-text search
-- across title, author, and repo_slug without a full sequential scan.

CREATE TABLE IF NOT EXISTS open_pull_requests (
    workspace     TEXT        NOT NULL,
    repo_slug     TEXT        NOT NULL,
    pr_id         TEXT        NOT NULL,
    pr_url        TEXT,
    title         TEXT,
    source_branch TEXT,
    target_branch TEXT,
    author        TEXT,
    status        TEXT        NOT NULL DEFAULT 'OPEN',
    created_on    TEXT,
    updated_on    TEXT,
    cached_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace, repo_slug, pr_id)
);

CREATE INDEX IF NOT EXISTS idx_open_prs_status
    ON open_pull_requests (status);

CREATE INDEX IF NOT EXISTS idx_open_prs_cached_at
    ON open_pull_requests (cached_at);

CREATE INDEX IF NOT EXISTS idx_open_prs_search
    ON open_pull_requests
    USING gin(to_tsvector('simple',
        coalesce(title, '') || ' ' ||
        coalesce(author, '') || ' ' ||
        coalesce(repo_slug, '')));
