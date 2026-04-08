-- GIN index on job_history.request_payload to make the failedJobId lookup in
-- hasRecentSuccessfulSelfAnalysisForJob efficient.
--
-- AIW: the upstream used CREATE INDEX CONCURRENTLY to avoid locking the table
-- during deployment, but CONCURRENTLY deadlocks Flyway (Flyway holds an
-- advisory-lock session as 'idle in transaction', which CIC waits on forever).
-- Plain CREATE INDEX is instantaneous on an empty table during first-run
-- migrations, and only briefly locks the table on in-place upgrades of
-- existing deployments — a trade-off we're willing to make for reliable
-- first-boot.
CREATE INDEX IF NOT EXISTS idx_job_history_payload_gin
    ON job_history USING gin (request_payload);
