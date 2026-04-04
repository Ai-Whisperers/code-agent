-- Remove the legacy inline `teams` JSONB column from products.
-- Team membership is now managed via the teams / team_members / product_teams tables
-- introduced in V88.

ALTER TABLE products DROP COLUMN IF EXISTS teams;
