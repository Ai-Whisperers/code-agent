-- Tracks developer feedback on individual review findings.
-- Enables false-positive rate metrics and auto-suppression of recurring noise.
CREATE TABLE comment_feedback (
    id          BIGSERIAL PRIMARY KEY,
    comment_id  BIGINT      NOT NULL,
    pr_id       TEXT        NOT NULL,
    workspace   TEXT        NOT NULL,
    repo_slug   TEXT        NOT NULL,
    feedback    TEXT        NOT NULL, -- 'false_positive', 'helpful', 'disagree'
    category    TEXT,                 -- copied from the original finding
    pattern     TEXT,                 -- normalised description for grouping duplicates
    created_by  TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_comment_feedback_repo    ON comment_feedback(workspace, repo_slug, feedback);
CREATE INDEX idx_comment_feedback_comment ON comment_feedback(comment_id);
CREATE INDEX idx_comment_feedback_pattern ON comment_feedback(workspace, repo_slug, pattern);
