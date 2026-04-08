-- Unified knowledge embedding table for Jira issues, Confluence pages,
-- and Jira attachments. Uses the existing pgvector extension.
-- source_type values: 'jira', 'confluence', 'jira-attachment'

CREATE TABLE knowledge_embeddings (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    source_type   TEXT         NOT NULL,
    source_id     TEXT         NOT NULL,
    product_id    TEXT,
    customer_id   TEXT,
    title         TEXT,
    content_chunk TEXT         NOT NULL,
    metadata      JSONB        NOT NULL DEFAULT '{}',
    embedding     vector(1024) NOT NULL,
    indexed_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Deduplication: same source + same content (by hash) is unique
CREATE UNIQUE INDEX idx_knowledge_source
    ON knowledge_embeddings(source_type, source_id, md5(content_chunk));

-- Filter by product/source without a vector scan
CREATE INDEX idx_knowledge_product
    ON knowledge_embeddings(source_type, product_id);

-- IVFFlat index for approximate nearest-neighbour cosine search
CREATE INDEX idx_knowledge_vector
    ON knowledge_embeddings USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
