CREATE TABLE agent_settings (
    key         VARCHAR(255) PRIMARY KEY,
    value       TEXT         NOT NULL,
    is_secret   BOOLEAN      NOT NULL DEFAULT FALSE,
    description TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  agent_settings           IS 'Runtime-editable application settings. Secret values are AES-256-GCM encrypted.';
COMMENT ON COLUMN agent_settings.key       IS 'MicroProfile Config key (e.g. anthropic.api.key)';
COMMENT ON COLUMN agent_settings.value     IS 'Plaintext for non-secrets; Base64(IV||ciphertext||tag) for secrets';
COMMENT ON COLUMN agent_settings.is_secret IS 'When true, value is encrypted and masked in API responses';
