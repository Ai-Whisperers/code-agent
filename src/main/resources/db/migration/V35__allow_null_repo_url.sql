-- Allow repo_url to be null for non-repository plans (JIRA, Confluence, etc.)
ALTER TABLE execution_plans ALTER COLUMN repo_url DROP NOT NULL;