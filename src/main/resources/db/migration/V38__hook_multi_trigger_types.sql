-- Add trigger_types JSONB column to support multiple trigger types per hook
ALTER TABLE automation_hooks ADD COLUMN trigger_types JSONB;

-- Migrate existing trigger_type values to trigger_types arrays
UPDATE automation_hooks SET trigger_types = jsonb_build_array(trigger_type) WHERE trigger_type IS NOT NULL;

-- Make trigger_types NOT NULL (all rows should have been migrated)
ALTER TABLE automation_hooks ALTER COLUMN trigger_types SET NOT NULL;

-- Drop the old trigger_type column
ALTER TABLE automation_hooks DROP COLUMN trigger_type;

-- Drop old indexes that referenced trigger_type
DROP INDEX IF EXISTS idx_automation_hooks_trigger_type;
DROP INDEX IF EXISTS idx_automation_hooks_enabled_trigger;

-- Add new GIN index for JSONB containment queries on trigger_types
CREATE INDEX idx_automation_hooks_trigger_types ON automation_hooks USING GIN (trigger_types);

-- Add index on enabled + trigger_types for the common query pattern
CREATE INDEX idx_automation_hooks_enabled_trigger_types ON automation_hooks (enabled) WHERE enabled = true;
