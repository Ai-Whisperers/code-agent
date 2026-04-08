CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE code_embeddings (
    id           BIGSERIAL PRIMARY KEY,
    workspace    TEXT NOT NULL,
    repo_slug    TEXT NOT NULL,
    file_path    TEXT NOT NULL,
    symbol_name  TEXT NOT NULL,
    symbol_type  TEXT NOT NULL,
    source_text  TEXT NOT NULL,
    line_start   INTEGER,
    line_end     INTEGER,
    embedding    vector(1024) NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_embeddings_unique
    ON code_embeddings (workspace, repo_slug, file_path, symbol_name);

CREATE INDEX idx_embeddings_repo
    ON code_embeddings (workspace, repo_slug);

CREATE INDEX idx_embeddings_vector
    ON code_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

ALTER TABLE repo_settings ADD COLUMN vector_enabled BOOLEAN NOT NULL DEFAULT FALSE;
