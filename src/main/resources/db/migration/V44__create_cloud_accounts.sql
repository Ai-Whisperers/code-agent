-- Cloud accounts: named provider credentials (AWS, Azure, Google, etc.)
-- Credentials are stored as an AES-256-GCM encrypted JSON blob so adding
-- new providers never requires a schema change.

CREATE TABLE cloud_accounts (
    id          TEXT PRIMARY KEY,
    name        TEXT        NOT NULL,
    description TEXT,
    type        TEXT        NOT NULL DEFAULT 'AWS',
    credentials TEXT,                -- encrypted JSON blob, see SettingsEncryption
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Link a customer to one cloud account (optional).
ALTER TABLE customers
    ADD COLUMN cloud_account_id TEXT REFERENCES cloud_accounts(id) ON DELETE SET NULL;
