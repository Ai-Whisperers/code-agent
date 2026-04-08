ALTER TABLE execution_plans ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE execution_plans ADD COLUMN IF NOT EXISTS created_by TEXT;

CREATE INDEX IF NOT EXISTS idx_plans_archived ON execution_plans(archived);
