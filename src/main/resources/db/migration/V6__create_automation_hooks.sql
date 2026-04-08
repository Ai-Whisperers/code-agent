CREATE TABLE automation_hooks (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    trigger_type    TEXT NOT NULL,
    pr_event        TEXT,
    branch_pattern  TEXT,
    cron_expr       TEXT,
    action_type     TEXT NOT NULL,
    prompt          TEXT NOT NULL,
    rule_names      TEXT,
    extra_rules     TEXT,
    target_branch   TEXT,
    commit_direct   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE repo_settings ADD COLUMN disabled_hooks TEXT;

INSERT INTO automation_hooks (name, description, enabled, trigger_type, pr_event,
    branch_pattern, action_type, prompt, commit_direct)
VALUES (
    'update-readme',
    'Update README.md after a PR is merged to develop',
    TRUE,
    'pr_event',
    'pullrequest:fulfilled',
    '^develop$',
    'FIX',
    'Analyze the current codebase structure, REST endpoints, configuration properties, '
    || 'database tables, and project layout. Update README.md to accurately reflect the '
    || 'current state. Do not remove existing documentation that is still valid. '
    || 'Focus on: new/changed endpoints, new configuration variables, new database tables, '
    || 'and updated project structure.',
    TRUE
);
