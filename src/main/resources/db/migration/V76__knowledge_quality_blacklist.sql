-- Tracks Jira issues (and other knowledge sources) that were rejected by the
-- Claude quality filter so they are not re-evaluated on every subsequent resync.
--
-- source_type  matches KnowledgeEmbeddingStore.KnowledgeChunk#sourceType  (e.g. 'jira')
-- source_id    matches KnowledgeEmbeddingStore.KnowledgeChunk#sourceId    (e.g. 'ENG-123')
-- reason       short human-readable label, e.g. 'claude-quality-filter'
-- rejected_at  timestamp of the first rejection
-- content_hash md5 of the issue text at rejection time — if the issue is later edited and
--              the hash changes, the blacklist entry is stale and the issue gets re-evaluated.

CREATE TABLE IF NOT EXISTS knowledge_quality_blacklist (
    id           BIGSERIAL PRIMARY KEY,
    source_type  TEXT        NOT NULL,
    source_id    TEXT        NOT NULL,
    reason       TEXT        NOT NULL DEFAULT 'claude-quality-filter',
    content_hash TEXT        NOT NULL,
    rejected_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_quality_blacklist UNIQUE (source_type, source_id)
);

CREATE INDEX IF NOT EXISTS idx_quality_blacklist_lookup
    ON knowledge_quality_blacklist (source_type, source_id);
