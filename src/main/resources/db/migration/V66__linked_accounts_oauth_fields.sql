-- Add OAuth-specific columns to user_linked_accounts.
-- auth_type: 'apitoken' (default) or 'oauth'
-- refresh_token_enc: encrypted OAuth refresh token (null for API-token accounts)
ALTER TABLE user_linked_accounts
    ADD COLUMN IF NOT EXISTS auth_type         VARCHAR NOT NULL DEFAULT 'apitoken',
    ADD COLUMN IF NOT EXISTS refresh_token_enc TEXT;
