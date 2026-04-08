-- Move environments from products to customers.
-- Each customer now owns its deployment environments directly.

ALTER TABLE customers
    ADD COLUMN environments JSONB NOT NULL DEFAULT '[]';

ALTER TABLE products
    DROP COLUMN IF EXISTS environments;
