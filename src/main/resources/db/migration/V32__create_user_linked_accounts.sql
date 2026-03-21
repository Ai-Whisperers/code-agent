CREATE TABLE user_linked_accounts (
  id             BIGSERIAL PRIMARY KEY,
  user_id        VARCHAR   NOT NULL,
  provider       VARCHAR   NOT NULL,
  display_name   VARCHAR,
  base_url       VARCHAR   NOT NULL,
  username       VARCHAR   NOT NULL,
  api_token_enc  TEXT      NOT NULL,
  created_at     TIMESTAMPTZ DEFAULT now(),
  updated_at     TIMESTAMPTZ DEFAULT now(),
  UNIQUE (user_id, provider)
);

CREATE INDEX idx_user_linked_accounts_user_id ON user_linked_accounts(user_id);
