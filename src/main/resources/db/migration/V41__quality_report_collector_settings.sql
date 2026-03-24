-- Settings used by QualityReportCollector (migrated from @ConfigProperty to SettingsService).
INSERT INTO agent_settings (key, value, is_secret, description, created_at, updated_at)
VALUES
    ('quality-report.cc-threshold',        NULL, false, 'Cyclomatic complexity threshold to flag in quality reports',            NOW(), NOW()),
    ('quality-report.coverage.enabled',    NULL, false, 'Enable coverage measurement during quality report collection',          NOW(), NOW()),
    ('quality-report.job-timeout-minutes', NULL, false, 'Timeout (minutes) for a single quality report collection job',         NOW(), NOW())
ON CONFLICT (key) DO NOTHING;
