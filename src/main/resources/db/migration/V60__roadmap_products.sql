-- Links a roadmap to one or more products so the AI improvement loop can
-- use the product's knowledge base, code graph and semantic index as context.
CREATE TABLE roadmap_products (
    roadmap_id  UUID         NOT NULL,
    product_id  VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (roadmap_id, product_id),
    CONSTRAINT fk_rp_roadmap  FOREIGN KEY (roadmap_id)  REFERENCES roadmaps(id)  ON DELETE CASCADE,
    CONSTRAINT fk_rp_product  FOREIGN KEY (product_id)  REFERENCES products(product_id) ON DELETE CASCADE
);
