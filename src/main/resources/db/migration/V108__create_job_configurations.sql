CREATE TABLE job_configurations (
    job_type          VARCHAR(50)  PRIMARY KEY,
    model_tier        VARCHAR(10)  NOT NULL DEFAULT 'DEFAULT',
    thinking_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
    thinking_budget   INTEGER,
    max_output_tokens INTEGER,
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  job_configurations                    IS 'Per-job-type AI model and thinking configuration';
COMMENT ON COLUMN job_configurations.model_tier         IS 'FAST=Haiku 4.5, DEFAULT=Sonnet 4.6, HIGH=Opus 4.6';
COMMENT ON COLUMN job_configurations.thinking_budget    IS 'NULL = service default (5000 for CHAT, 10000 for others)';
COMMENT ON COLUMN job_configurations.max_output_tokens  IS 'NULL = tier default (8192 for FAST, global anthropic.max-tokens otherwise)';
