-- GIN index on job_history.request_payload to make the failedJobId lookup in
-- hasRecentSuccessfulSelfAnalysisForJob efficient.
-- CONCURRENTLY avoids locking the table during deployment.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_history_payload_gin
    ON job_history USING gin (request_payload);
