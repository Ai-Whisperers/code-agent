-- Test plans belong to a feature (issue_key), not to a scope.
-- Multiple scopes can reference the same feature's test plan via the join table.
CREATE TABLE qa_test_plans (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  issue_key               TEXT NOT NULL UNIQUE,
  analysis_text           TEXT,
  plan_json               JSONB,
  specifications          JSONB,
  generated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  generated_by            TEXT,
  analysis_edited         BOOLEAN NOT NULL DEFAULT FALSE,

  -- KPIs extracted from plan_json on save (for fast querying / dashboards)
  kpi_story_count               INT,
  kpi_behaviour_tc_count        INT,
  kpi_capability_tc_count       INT,
  kpi_risk_count                INT,
  kpi_open_clarifications       INT,
  kpi_coverage_pct              NUMERIC(5,2),
  kpi_high_risks                INT,
  kpi_gaps_count                INT,
  kpi_readiness                 TEXT,

  -- Requirements drift tracking
  kpi_spec_hash                 TEXT,
  kpi_drift_detected_at         TIMESTAMPTZ,
  kpi_regen_count               INT NOT NULL DEFAULT 0,
  kpi_analysis_edit_count       INT NOT NULL DEFAULT 0,

  -- Jira / Xray sync
  jira_issue_key                TEXT,          -- optional link to a Jira test-plan issue
  xray_sync_status              TEXT NOT NULL DEFAULT 'pending',  -- pending | synced | error
  xray_synced_at                TIMESTAMPTZ
);

-- n:m join: which scopes "view" which test plans
CREATE TABLE qa_scope_test_plans (
  scope_id   UUID NOT NULL REFERENCES scopes(id) ON DELETE CASCADE,
  plan_id    UUID NOT NULL REFERENCES qa_test_plans(id) ON DELETE CASCADE,
  PRIMARY KEY (scope_id, plan_id)
);

-- History table: one row per regeneration, enabling trend charts over time
CREATE TABLE qa_test_plan_history (
  id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plan_id                  UUID NOT NULL REFERENCES qa_test_plans(id) ON DELETE CASCADE,
  issue_key                TEXT NOT NULL,
  snapshot_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  kpi_behaviour_tc_count   INT,
  kpi_capability_tc_count  INT,
  kpi_risk_count           INT,
  kpi_open_clarifications  INT,
  kpi_coverage_pct         NUMERIC(5,2),
  kpi_high_risks           INT,
  kpi_gaps_count           INT,
  kpi_readiness            TEXT,
  kpi_spec_hash            TEXT,
  trigger                  TEXT
);
