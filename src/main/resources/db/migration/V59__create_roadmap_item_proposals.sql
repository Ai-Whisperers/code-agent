CREATE TABLE IF NOT EXISTS roadmap_item_proposals (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    roadmap_id           UUID         NOT NULL REFERENCES roadmaps(id) ON DELETE CASCADE,
    issue_key            VARCHAR(64)  NOT NULL,
    issue_type           VARCHAR(16)  NOT NULL,
    parent_key           VARCHAR(64),
    proposed_summary     TEXT,
    proposed_description TEXT,
    proposed_criteria    TEXT,
    proposed_technical   TEXT,
    ai_explanation       TEXT,
    status               VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    jira_result_key      VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_proposals_roadmap_issue
    ON roadmap_item_proposals (roadmap_id, issue_key);
