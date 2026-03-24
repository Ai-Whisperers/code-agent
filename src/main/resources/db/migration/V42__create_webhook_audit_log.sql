CREATE TABLE webhook_audit_log (
    id             bigserial    PRIMARY KEY,
    platform       varchar(50)  NOT NULL,
    event_type     varchar(100) NOT NULL,
    workspace      varchar(255),
    repo_slug      varchar(255),
    pr_id          varchar(50),
    author         varchar(255),
    action         varchar(100) NOT NULL,
    hooks_executed jsonb,
    payload        text,
    received_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_audit_platform    ON webhook_audit_log (platform);
CREATE INDEX idx_webhook_audit_workspace   ON webhook_audit_log (workspace, repo_slug);
CREATE INDEX idx_webhook_audit_received_at ON webhook_audit_log (received_at DESC);
