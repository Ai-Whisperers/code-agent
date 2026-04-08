-- Migrate roadmaps.label (single) → roadmap_labels (many-to-many)
-- The legacy label column is preserved for the duration of a phased rollout;
-- a later migration (V65) can drop it once all code paths use roadmap_labels.

-- 1. Create the join table
CREATE TABLE roadmap_labels (
    roadmap_id UUID        NOT NULL REFERENCES roadmaps(id) ON DELETE CASCADE,
    label      VARCHAR(255) NOT NULL,
    position   SMALLINT     NOT NULL DEFAULT 0, -- display order
    PRIMARY KEY (roadmap_id, label)
);

CREATE INDEX idx_roadmap_labels_roadmap_id ON roadmap_labels(roadmap_id);
CREATE INDEX idx_roadmap_labels_label      ON roadmap_labels(label);

-- 2. Back-fill from the existing single label column (skip NULL / blank rows)
INSERT INTO roadmap_labels (roadmap_id, label, position)
SELECT id, label, 0
FROM   roadmaps
WHERE  label IS NOT NULL AND label <> '';
