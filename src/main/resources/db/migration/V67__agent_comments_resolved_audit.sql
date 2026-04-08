-- Track who resolved each agent comment and when.
-- resolved_at: wall-clock time the finding was marked resolved (null = still open)
-- resolved_by: actor string — "Review Agent" for bot auto-resolution,
--              or the developer's SCM username when they reply/resolve manually via webhook.
ALTER TABLE agent_comments
    ADD COLUMN IF NOT EXISTS resolved_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolved_by  VARCHAR;
