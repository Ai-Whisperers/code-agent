ALTER TABLE repo_settings
    ADD COLUMN IF NOT EXISTS description      TEXT,
    ADD COLUMN IF NOT EXISTS primary_language TEXT,
    ADD COLUMN IF NOT EXISTS jira_components  TEXT,
    ADD COLUMN IF NOT EXISTS tags             TEXT;
