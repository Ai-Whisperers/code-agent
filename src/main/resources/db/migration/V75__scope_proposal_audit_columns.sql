-- Track who last saved and who synced to Jira
ALTER TABLE scope_item_proposals
    ADD COLUMN IF NOT EXISTS updated_by TEXT,
    ADD COLUMN IF NOT EXISTS synced_by  TEXT;
