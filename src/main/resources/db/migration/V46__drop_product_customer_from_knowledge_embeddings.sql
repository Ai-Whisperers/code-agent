-- Knowledge embeddings are common knowledge, not scoped to a product or customer.
-- The application no longer reads or writes these columns (since V46 code changes),
-- so drop them and the product/source composite index they powered.

DROP INDEX IF EXISTS idx_knowledge_product;

ALTER TABLE knowledge_embeddings
    DROP COLUMN IF EXISTS product_id,
    DROP COLUMN IF EXISTS customer_id;
