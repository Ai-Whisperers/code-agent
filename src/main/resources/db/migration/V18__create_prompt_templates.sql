CREATE TABLE IF NOT EXISTS prompt_templates (
    prompt_key  VARCHAR(64)  PRIMARY KEY,
    content     TEXT         NOT NULL,
    description VARCHAR(255),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
