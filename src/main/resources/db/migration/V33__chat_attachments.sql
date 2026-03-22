-- Chat attachments: file uploads linked to conversations and messages
CREATE TABLE chat_attachments (
    id               BIGSERIAL   PRIMARY KEY,
    attachment_id    TEXT        NOT NULL UNIQUE DEFAULT gen_random_uuid()::text,
    conversation_id  TEXT        NOT NULL REFERENCES chat_conversations(conversation_id) ON DELETE CASCADE,
    message_id       BIGINT      REFERENCES chat_messages(id) ON DELETE SET NULL,
    filename         TEXT        NOT NULL,
    content_type     TEXT        NOT NULL,
    file_size        BIGINT      NOT NULL,
    s3_bucket        TEXT        NOT NULL,
    s3_key          TEXT        NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for fast lookup of attachments by conversation
CREATE INDEX idx_chat_attachments_conversation ON chat_attachments(conversation_id, uploaded_at DESC);

-- Index for fast lookup of attachments by message
CREATE INDEX idx_chat_attachments_message ON chat_attachments(message_id) WHERE message_id IS NOT NULL;

-- Index for fast lookup by attachment_id (for direct access)
CREATE INDEX idx_chat_attachments_id ON chat_attachments(attachment_id);
