-- Persists in-flight plan jobs so PlanOrchestratorService can rehydrate
-- its in-memory trackedJobs map after a process restart.
-- A row exists for every job that was dispatched by the orchestrator but has
-- not yet completed (i.e. it is currently QUEUED or RUNNING in the jobs table).
-- Rows are inserted in submitPhase/executeStep and deleted in onJobCompleted/cleanup.
CREATE TABLE IF NOT EXISTS plan_tracked_jobs (
    job_id       TEXT        NOT NULL PRIMARY KEY,
    plan_id      TEXT        NOT NULL,
    step_id      TEXT        NOT NULL,
    phase_order  INTEGER     NOT NULL,
    is_metrics   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_plan_tracked_jobs_plan_id ON plan_tracked_jobs (plan_id);
