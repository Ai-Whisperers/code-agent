CREATE TABLE ai_calls (
    id                          BIGSERIAL PRIMARY KEY,
    job_id                      TEXT,
    job_type                    TEXT,
    model                       TEXT NOT NULL,
    iteration                   INTEGER,
    input_tokens                BIGINT NOT NULL DEFAULT 0,
    output_tokens               BIGINT NOT NULL DEFAULT 0,
    cache_creation_input_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_input_tokens     BIGINT NOT NULL DEFAULT 0,
    stop_reason                 TEXT,
    tool_names                  TEXT,
    duration_ms                 BIGINT NOT NULL DEFAULT 0,
    is_error                    BOOLEAN NOT NULL DEFAULT FALSE,
    error_message               TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_calls_job_id ON ai_calls(job_id);
CREATE INDEX idx_ai_calls_created_at ON ai_calls(created_at);
CREATE INDEX idx_ai_calls_job_type ON ai_calls(job_type);
