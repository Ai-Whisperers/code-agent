-- Create conversation_context table to store context references for chat conversations

CREATE TABLE conversation_context (
    conversation_id TEXT PRIMARY KEY,
    customer_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    product_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    aikido_issue_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    jira_issue_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    confluence_doc_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for conversation lookup
CREATE INDEX idx_conversation_context_conversation_id ON conversation_context(conversation_id);

-- Index for searching by customer context
CREATE INDEX idx_conversation_context_customer_ids ON conversation_context USING GIN(customer_ids);

-- Index for searching by product context  
CREATE INDEX idx_conversation_context_product_ids ON conversation_context USING GIN(product_ids);

-- Index for temporal queries
CREATE INDEX idx_conversation_context_created_at ON conversation_context(created_at);
