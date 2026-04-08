-- Per-roadmap issue-type configuration.
-- Allows different projects to use different Jira naming conventions.
-- Defaults match the global settings so existing roadmaps keep working.
ALTER TABLE roadmaps
    ADD COLUMN IF NOT EXISTS epic_issuetype      VARCHAR(100) NOT NULL DEFAULT 'Epic',
    ADD COLUMN IF NOT EXISTS feature_issuetype   VARCHAR(100) NOT NULL DEFAULT 'Story',
    ADD COLUMN IF NOT EXISTS userstory_issuetype VARCHAR(100) NOT NULL DEFAULT 'Sub-task';

-- Jira last-modified timestamp, populated during sync.
-- Used to skip AI re-review when the issue has not changed since the last review.
ALTER TABLE roadmap_items
    ADD COLUMN IF NOT EXISTS jira_modified_at TIMESTAMPTZ;
