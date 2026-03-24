CREATE TABLE audit_log (
    id            BIGSERIAL     PRIMARY KEY,
    actor         VARCHAR(255)  NOT NULL,
    category      VARCHAR(64)   NOT NULL,
    action        VARCHAR(64)   NOT NULL,
    resource_type VARCHAR(64),
    resource_id   VARCHAR(255),
    detail        JSONB,
    occurred_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_occurred_at ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_log_category    ON audit_log (category);
CREATE INDEX idx_audit_log_actor       ON audit_log (actor);
