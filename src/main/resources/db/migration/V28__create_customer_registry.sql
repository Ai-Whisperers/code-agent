-- Customer registry: top-level customers and their products.
-- Products carry environments, teams, Jira/Confluence/Git config as JSONB.

CREATE TABLE customers (
    customer_id   TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    metadata      JSONB NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE products (
    product_id    TEXT PRIMARY KEY,
    customer_id   TEXT NOT NULL REFERENCES customers(customer_id) ON DELETE CASCADE,
    display_name  TEXT NOT NULL,
    git           JSONB NOT NULL DEFAULT '{}',
    jira          JSONB NOT NULL DEFAULT '{}',
    confluence    JSONB NOT NULL DEFAULT '{}',
    environments  JSONB NOT NULL DEFAULT '[]',
    teams         JSONB NOT NULL DEFAULT '{}',
    metadata      JSONB NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_products_customer ON products(customer_id);

-- Optional: link repo_settings rows to a product
ALTER TABLE repo_settings ADD COLUMN IF NOT EXISTS product_id TEXT REFERENCES products(product_id);
CREATE INDEX IF NOT EXISTS idx_repo_settings_product ON repo_settings(product_id);
