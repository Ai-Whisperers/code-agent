CREATE TABLE integration_filters (
    id               BIGSERIAL PRIMARY KEY,
    integration_type VARCHAR(20)  NOT NULL,
    key              VARCHAR(255) NOT NULL,
    name             TEXT         NOT NULL DEFAULT '',
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    webhook_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (integration_type, key)
);

COMMENT ON TABLE  integration_filters                  IS 'Per-project / per-space enable/disable flags for Jira and Confluence integrations.';
COMMENT ON COLUMN integration_filters.integration_type IS 'Either ''jira'' or ''confluence''.';
COMMENT ON COLUMN integration_filters.key              IS 'Jira project key or Confluence space key.';
COMMENT ON COLUMN integration_filters.name             IS 'Display name cached from the live API; refreshed on every PUT.';
COMMENT ON COLUMN integration_filters.enabled          IS 'When false the project/space is excluded from indexing, webhooks, and UI selectors.';
COMMENT ON COLUMN integration_filters.webhook_enabled  IS 'When false incoming webhooks for this project/space are silently ignored even if enabled=true.';

CREATE INDEX idx_integration_filters_type ON integration_filters (integration_type);
