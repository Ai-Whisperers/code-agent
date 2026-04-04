ALTER TABLE execution_plans
    ADD COLUMN IF NOT EXISTS source_repo_url TEXT;
