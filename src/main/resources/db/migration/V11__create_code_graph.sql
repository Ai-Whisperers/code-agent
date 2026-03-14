CREATE TABLE code_graph_nodes (
    id          BIGSERIAL PRIMARY KEY,
    workspace   TEXT NOT NULL,
    repo_slug   TEXT NOT NULL,
    file_path   TEXT NOT NULL,
    symbol_name TEXT NOT NULL,
    symbol_type TEXT NOT NULL,
    line_start  INTEGER,
    line_end    INTEGER,
    modifiers   TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_cgn_symbol
    ON code_graph_nodes(workspace, repo_slug, file_path, symbol_name);
CREATE INDEX idx_cgn_repo
    ON code_graph_nodes(workspace, repo_slug);

CREATE TABLE code_graph_edges (
    id          BIGSERIAL PRIMARY KEY,
    workspace   TEXT NOT NULL,
    repo_slug   TEXT NOT NULL,
    source_node TEXT NOT NULL,
    target_node TEXT NOT NULL,
    edge_type   TEXT NOT NULL,
    source_file TEXT,
    target_file TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_cge_unique
    ON code_graph_edges(workspace, repo_slug, source_node, target_node, edge_type);
CREATE INDEX idx_cge_target
    ON code_graph_edges(workspace, repo_slug, target_node);
CREATE INDEX idx_cge_source
    ON code_graph_edges(workspace, repo_slug, source_node);
