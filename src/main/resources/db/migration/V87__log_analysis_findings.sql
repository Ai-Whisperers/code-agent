-- Log Analysis Findings: dedup cache and findings surface for the scheduled log analyser.
--
-- One row per unique (fingerprint, customer_id, environment_name).
-- The same row is updated on every re-occurrence — no separate cache table needed.
--
-- Lifecycle:
--   suppress_until > now()  → Gate 1 skip; occurrence_count + last_seen_at updated
--   Haiku triage            → Gate 2; ai_decision set to 'GENUINE' or 'NOISE', suppress_until = now() + 24h
--   GENUINE rows with status='OPEN' are surfaced in the UI findings screen.
--   Rows with last_seen_at older than 90 days are pruned automatically by LogAnalysisService.

CREATE TABLE log_analysis_findings (
    id               BIGSERIAL    PRIMARY KEY,
    fingerprint      TEXT         NOT NULL,
    customer_id      TEXT         NOT NULL,
    environment_name TEXT         NOT NULL,
    log_group_name   TEXT         NOT NULL,
    exception_class  TEXT,
    top_frames       TEXT,
    sample_message   TEXT,
    first_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    occurrence_count INT          NOT NULL DEFAULT 1,
    suppress_until   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ai_decision      TEXT,        -- 'GENUINE', 'NOISE'
    severity         TEXT,        -- 'high', 'medium', 'low'
    ai_reason        TEXT,
    status           TEXT         NOT NULL DEFAULT 'OPEN'  -- 'OPEN', 'DISMISSED'
);

COMMENT ON TABLE log_analysis_findings IS
    'Dedup cache and findings surface for the scheduled log analyser. '
    'One row per unique exception fingerprint per customer environment. '
    'Rows with last_seen_at older than 90 days are pruned automatically.';

CREATE UNIQUE INDEX log_analysis_findings_fp_env_idx
    ON log_analysis_findings (fingerprint, customer_id, environment_name);

CREATE INDEX log_analysis_findings_suppress_idx
    ON log_analysis_findings (suppress_until);

CREATE INDEX log_analysis_findings_decision_status_idx
    ON log_analysis_findings (ai_decision, status);

CREATE INDEX log_analysis_findings_last_seen_idx
    ON log_analysis_findings (last_seen_at DESC);
