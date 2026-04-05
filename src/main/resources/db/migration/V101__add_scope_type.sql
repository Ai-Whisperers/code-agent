-- Add scope_type to distinguish PO scopes (product/roadmap) from QA scopes (test plans).
-- Existing scopes default to 'po'.
ALTER TABLE scopes
    ADD COLUMN scope_type TEXT NOT NULL DEFAULT 'po';

CREATE INDEX idx_scopes_scope_type ON scopes (scope_type);
