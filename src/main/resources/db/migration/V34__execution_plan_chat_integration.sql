-- Add fields to existing execution_plans table for chat integration
ALTER TABLE execution_plans ADD COLUMN IF NOT EXISTS conversation_id TEXT;
ALTER TABLE execution_plans ADD COLUMN IF NOT EXISTS markdown_content TEXT;
ALTER TABLE execution_plans ADD COLUMN IF NOT EXISTS workspace_path TEXT;

-- Index for chat-generated plans
CREATE INDEX idx_plans_conversation ON execution_plans(conversation_id) WHERE conversation_id IS NOT NULL;

-- Index for workspace path lookup
CREATE INDEX idx_plans_workspace_path ON execution_plans(workspace_path) WHERE workspace_path IS NOT NULL;
