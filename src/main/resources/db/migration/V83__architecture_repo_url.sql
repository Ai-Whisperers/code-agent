-- Add repo_url column so generate-all can reconstruct full URLs from stored slugs.
-- Nullable: existing rows have no URL; populated on every new generate call.
ALTER TABLE architecture_diagram_versions
    ADD COLUMN IF NOT EXISTS repo_url TEXT;
