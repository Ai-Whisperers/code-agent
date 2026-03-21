-- Chat conversations: one row per conversation (metadata + ownership)
CREATE TABLE chat_conversations (
    conversation_id  TEXT        PRIMARY KEY DEFAULT gen_random_uuid()::text,
    user_id          TEXT        NOT NULL,
    title            TEXT        NOT NULL,
    product_id       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Fast lookup of all conversations for a user, sorted by most recent
CREATE INDEX idx_chat_conv_user ON chat_conversations(user_id, updated_at DESC);

-- Individual messages within a conversation (role + serialised Anthropic MessageParam content)
CREATE TABLE chat_messages (
    id               BIGSERIAL   PRIMARY KEY,
    conversation_id  TEXT        NOT NULL
                     REFERENCES chat_conversations(conversation_id) ON DELETE CASCADE,
    message_json     JSONB       NOT NULL,
    sequence_num     INTEGER     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_msg_conv ON chat_messages(conversation_id, sequence_num);
