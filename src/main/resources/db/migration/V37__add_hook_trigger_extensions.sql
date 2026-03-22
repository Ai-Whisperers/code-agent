-- Add repoUrl and triggerFilter columns to automation_hooks table
ALTER TABLE automation_hooks 
ADD COLUMN repo_url TEXT,
ADD COLUMN trigger_filter JSONB;

-- Add index on trigger_type for faster lookups
CREATE INDEX idx_automation_hooks_trigger_type ON automation_hooks (trigger_type);

-- Add index on enabled + trigger_type for the common query pattern
CREATE INDEX idx_automation_hooks_enabled_trigger ON automation_hooks (enabled, trigger_type);
