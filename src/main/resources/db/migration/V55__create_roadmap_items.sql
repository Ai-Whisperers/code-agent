-- Stores the Jira issue structure for a roadmap (epics, features, user stories).
-- This table is populated by the sync step (fetching from Jira) and is the
-- source of truth for tree views and review job dispatching.
-- AI review results live in jira_issue_reviews and are joined at query time.

CREATE TABLE IF NOT EXISTS roadmap_items (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    roadmap_id      UUID         NOT NULL REFERENCES roadmaps (id) ON DELETE CASCADE,
    issue_key       VARCHAR(64)  NOT NULL,
    issue_type      VARCHAR(16)  NOT NULL CHECK (issue_type IN ('EPIC', 'FEATURE', 'USERSTORY')),
    parent_key      VARCHAR(64),
    grandparent_key VARCHAR(64),
    summary         TEXT,
    jira_status     VARCHAR(64),
    synced_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_roadmap_items
    ON roadmap_items (roadmap_id, issue_key);
