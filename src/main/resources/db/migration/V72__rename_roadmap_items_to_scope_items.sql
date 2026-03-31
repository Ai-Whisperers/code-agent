-- Renames the roadmap_items table and its roadmap_id FK column to the
-- scope-centric naming convention used throughout the rest of the schema.

ALTER TABLE roadmap_items RENAME TO scope_items;

ALTER TABLE scope_items RENAME COLUMN roadmap_id TO scope_id;

ALTER INDEX IF EXISTS uidx_roadmap_items RENAME TO uidx_scope_items;
