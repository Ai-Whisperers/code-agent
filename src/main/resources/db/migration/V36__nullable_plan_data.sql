-- Allow plan_data to be null for markdown-only plans
ALTER TABLE execution_plans ALTER COLUMN plan_data DROP NOT NULL;
