-- Renames every remaining roadmap_* table and its roadmap_id FK column to
-- the scope-centric naming convention. roadmap_items / roadmap_id were already
-- handled in V72; this migration covers all other tables.

-- ── roadmaps → scopes ─────────────────────────────────────────────────────
ALTER TABLE roadmaps RENAME TO scopes;
ALTER INDEX IF EXISTS idx_roadmaps_label RENAME TO idx_scopes_label;

-- ── roadmap_labels → scope_labels ─────────────────────────────────────────
ALTER TABLE roadmap_labels RENAME TO scope_labels;
ALTER TABLE scope_labels RENAME COLUMN roadmap_id TO scope_id;
ALTER INDEX IF EXISTS idx_roadmap_labels_roadmap_id RENAME TO idx_scope_labels_scope_id;
ALTER INDEX IF EXISTS idx_roadmap_labels_label      RENAME TO idx_scope_labels_label;

-- ── roadmap_products → scope_products ─────────────────────────────────────
ALTER TABLE roadmap_products RENAME TO scope_products;
ALTER TABLE scope_products RENAME COLUMN roadmap_id TO scope_id;

-- ── roadmap_item_overrides → scope_item_overrides ─────────────────────────
ALTER TABLE roadmap_item_overrides RENAME TO scope_item_overrides;
ALTER TABLE scope_item_overrides RENAME COLUMN roadmap_id TO scope_id;
ALTER INDEX IF EXISTS uidx_roadmap_item_overrides RENAME TO uidx_scope_item_overrides;

-- ── roadmap_item_proposals → scope_item_proposals ─────────────────────────
ALTER TABLE roadmap_item_proposals RENAME TO scope_item_proposals;
ALTER TABLE scope_item_proposals RENAME COLUMN roadmap_id TO scope_id;
ALTER INDEX IF EXISTS idx_proposals_roadmap_issue RENAME TO idx_proposals_scope_issue;

-- ── jira_issue_reviews: rename roadmap_id column → scope_id ───────────────
ALTER TABLE jira_issue_reviews RENAME COLUMN roadmap_id TO scope_id;
ALTER INDEX IF EXISTS uidx_jira_issue_reviews_roadmap   RENAME TO uidx_jira_issue_reviews_scope;
ALTER INDEX IF EXISTS idx_jira_issue_reviews_roadmap_id RENAME TO idx_jira_issue_reviews_scope_id;
