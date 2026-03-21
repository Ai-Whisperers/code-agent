-- Add documentation generation and Confluence integration settings to repo_settings.

ALTER TABLE repo_settings ADD COLUMN docs_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE repo_settings ADD COLUMN confluence_space_key TEXT;
ALTER TABLE repo_settings ADD COLUMN confluence_parent_page_id TEXT;
