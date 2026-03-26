CREATE TABLE IF NOT EXISTS jira_issue_reviews (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    roadmap_id          UUID         REFERENCES roadmaps (id) ON DELETE CASCADE,
    issue_key           VARCHAR(64)  NOT NULL,
    issue_type          VARCHAR(16)  NOT NULL CHECK (issue_type IN ('EPIC', 'FEATURE', 'USERSTORY')),
    issue_summary       TEXT,
    parent_key          VARCHAR(64),
    jira_status         VARCHAR(64),
    readiness_score     INT          CHECK (readiness_score BETWEEN 0 AND 100),
    readiness_label     VARCHAR(64),
    complexity_score    INT          CHECK (complexity_score BETWEEN 0 AND 100),
    improvement_summary TEXT,
    review_json         JSONB,
    job_id              VARCHAR(64),
    reviewed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- For roadmap-scoped reviews: one row per (roadmap, issue)
CREATE UNIQUE INDEX IF NOT EXISTS uidx_jira_issue_reviews_roadmap
    ON jira_issue_reviews (roadmap_id, issue_key)
    WHERE roadmap_id IS NOT NULL;

-- For standalone (hook-triggered) reviews: one row per issue key
CREATE UNIQUE INDEX IF NOT EXISTS uidx_jira_issue_reviews_standalone
    ON jira_issue_reviews (issue_key)
    WHERE roadmap_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_jira_issue_reviews_roadmap_id
    ON jira_issue_reviews (roadmap_id);

CREATE INDEX IF NOT EXISTS idx_jira_issue_reviews_issue_key
    ON jira_issue_reviews (issue_key);
