CREATE TABLE IF NOT EXISTS roadmap_item_overrides (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    roadmap_id      UUID         NOT NULL REFERENCES roadmaps (id) ON DELETE CASCADE,
    issue_key       VARCHAR(64)  NOT NULL,
    override_status VARCHAR(16)  NOT NULL CHECK (override_status IN ('ACCEPTED', 'REMOVED')),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      VARCHAR(255)
);

-- One override row per item per roadmap
CREATE UNIQUE INDEX IF NOT EXISTS uidx_roadmap_item_overrides
    ON roadmap_item_overrides (roadmap_id, issue_key);
