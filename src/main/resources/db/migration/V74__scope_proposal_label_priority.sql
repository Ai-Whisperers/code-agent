ALTER TABLE scope_item_proposals
    ADD COLUMN IF NOT EXISTS proposed_label    TEXT,
    ADD COLUMN IF NOT EXISTS proposed_priority TEXT;
