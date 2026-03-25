-- Admin-uploaded static files indexed into the knowledge base.
-- Supported file types: .txt, .md, .pdf
-- source_type in knowledge_embeddings: 'static-file'
-- Files are stored in S3 under the key recorded in s3_key.

CREATE TABLE static_file_sources (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name              TEXT        NOT NULL,
    original_filename TEXT        NOT NULL,
    content_type      TEXT        NOT NULL,
    s3_key            TEXT        NOT NULL,
    file_size         BIGINT      NOT NULL,
    indexed_at        TIMESTAMPTZ,
    chunk_count       INTEGER,
    index_error       TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
