-- Web documentation sources for the crawler.
-- Each row configures one site to crawl (e.g. Quarkus guides, React docs).
-- source_type in knowledge_embeddings: 'web-docs'
-- No product/customer scoping — web docs are common knowledge available to all.

CREATE TABLE web_doc_sources (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT        NOT NULL,
    base_url            TEXT        NOT NULL,
    allowed_path_prefix TEXT        NOT NULL,
    max_pages           INTEGER     NOT NULL DEFAULT 500,
    crawl_delay_ms      INTEGER     NOT NULL DEFAULT 500,
    last_crawled_at     TIMESTAMPTZ,
    last_crawl_chunks   INTEGER,
    last_crawl_error    TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (base_url)
);
