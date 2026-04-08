-- Architecture diagram versions table.
-- Covers both repo-based (code architecture) and cloud environment diagrams in a single table.
-- Every write (AI-generated or human-edited) inserts a new immutable row — no in-place updates.
--
-- Scope rules:
--   repo diagrams:   repo_slug IS NOT NULL, customer_id IS NULL, environment IS NULL
--   cloud diagrams:  repo_slug IS NULL, customer_id IS NOT NULL, environment IS NOT NULL

CREATE TABLE architecture_diagram_versions (
    id            BIGSERIAL    PRIMARY KEY,

    -- Scope: exactly one of (repo_slug) or (customer_id + environment) must be set
    repo_slug     TEXT,
    customer_id   TEXT,
    environment   TEXT,

    view_name     TEXT         NOT NULL,
    view_type     TEXT         NOT NULL,   -- SystemContext | Container | Component | Cloud
    version       INT          NOT NULL,   -- monotonically increasing per (scope, view_name)
    source        TEXT         NOT NULL,   -- 'ai' | 'human'
    pinned        BOOLEAN      NOT NULL DEFAULT false,
    dsl_src       TEXT         NOT NULL,   -- full workspace DSL (scope-level, shared across views of same generation)
    mermaid_src   TEXT         NOT NULL,   -- rendered Mermaid for this specific view
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT scope_check CHECK (
        (repo_slug IS NOT NULL AND customer_id IS NULL AND environment IS NULL) OR
        (repo_slug IS NULL AND customer_id IS NOT NULL AND environment IS NOT NULL)
    )
);

-- Efficient lookup of latest version for a repo view
CREATE INDEX arch_versions_repo_view_idx
    ON architecture_diagram_versions (repo_slug, view_name, version DESC)
    WHERE repo_slug IS NOT NULL;

-- Efficient lookup of latest version for a cloud environment view
CREATE INDEX arch_versions_cloud_view_idx
    ON architecture_diagram_versions (customer_id, environment, view_name, version DESC)
    WHERE customer_id IS NOT NULL;

-- Fast lookup of all pinned versions (used when loading AI baseline)
CREATE INDEX arch_versions_pinned_idx
    ON architecture_diagram_versions (pinned)
    WHERE pinned = true;
