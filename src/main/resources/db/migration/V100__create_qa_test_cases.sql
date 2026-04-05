-- Individual test cases generated from a qa_test_plans row.
-- Each row represents one test case for a child story of the feature.
CREATE TABLE qa_test_cases (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plan_id             UUID NOT NULL REFERENCES qa_test_plans(id) ON DELETE CASCADE,
  feature_key         TEXT NOT NULL,          -- denormalized from qa_test_plans.issue_key for fast queries
  story_key           TEXT NOT NULL,          -- child story this TC covers
  test_case_id        TEXT NOT NULL,          -- e.g. BTC-209-01 / CTC-209-01
  title               TEXT NOT NULL,
  description         TEXT,
  pre_conditions      JSONB NOT NULL DEFAULT '[]',
  test_steps          JSONB NOT NULL DEFAULT '[]',
  expected_results    JSONB NOT NULL DEFAULT '[]',
  test_case_type      TEXT NOT NULL,          -- Behaviour | Capability
  priority            TEXT NOT NULL,          -- High | Medium | Low
  status              TEXT NOT NULL DEFAULT 'Open',  -- Open | Pass | Fail | Blocked
  estimated_duration  TEXT,                   -- raw string e.g. "5 mins"

  -- ── KPIs: complexity ──────────────────────────────────────────────────────
  kpi_step_count            INT,              -- number of test steps (complexity proxy)
  kpi_estimated_mins        INT,              -- parsed integer from estimated_duration
  kpi_precondition_count    INT,              -- number of pre-conditions

  -- ── KPIs: execution tracking ──────────────────────────────────────────────
  kpi_execution_count       INT NOT NULL DEFAULT 0,  -- times this TC has been run
  kpi_last_result           TEXT,            -- Pass | Fail | Blocked
  kpi_last_executed_at      TIMESTAMPTZ,

  -- ── KPIs: automation readiness ────────────────────────────────────────────
  kpi_automation_status     TEXT NOT NULL DEFAULT 'manual',  -- manual | automated | in_progress

  -- ── Jira / Xray sync ──────────────────────────────────────────────────────
  jira_issue_key      TEXT,                   -- set after Xray sync
  xray_sync_status    TEXT NOT NULL DEFAULT 'pending', -- pending | synced | error
  xray_synced_at      TIMESTAMPTZ,
  generated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (plan_id, test_case_id)
);

CREATE INDEX qa_test_cases_plan_id_idx    ON qa_test_cases (plan_id);
CREATE INDEX qa_test_cases_story_key_idx  ON qa_test_cases (story_key);
