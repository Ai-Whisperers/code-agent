-- Decouple products from customers:
-- 1. Drop the mandatory customer_id FK from products (products now exist independently).
-- 2. Add a customer_products join table for the many-to-many link.

-- Remove the old FK column from products
ALTER TABLE products DROP COLUMN IF EXISTS customer_id;

-- Join table: a product can be linked to multiple customers and vice-versa
CREATE TABLE customer_products (
    customer_id  TEXT NOT NULL REFERENCES customers(customer_id) ON DELETE CASCADE,
    product_id   TEXT NOT NULL REFERENCES products(product_id)   ON DELETE CASCADE,
    linked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, product_id)
);

CREATE INDEX idx_customer_products_product ON customer_products(product_id);
