INSERT INTO agent_settings (key, value, is_secret, description) VALUES
    ('roadmap.review.model',                    '',               false, 'Claude model for Jira readiness reviews (leave blank to fall back to anthropic.model)'),
    ('roadmap.review.max-tokens',               '4096',           false, 'Max output tokens for roadmap review responses'),
    ('roadmap.jira.epic-issuetype',             'Epic',           false, 'Jira issue type name for Epics'),
    ('roadmap.jira.feature-issuetype',          'Story',          false, 'Jira issue type name for Features'),
    ('roadmap.jira.userstory-issuetype',        'Sub-task',       false, 'Jira issue type name for User Stories'),
    ('roadmap.jira.status-map.new',             'To Do,Open,New', false, 'Comma-separated Jira statuses mapped to New'),
    ('roadmap.jira.status-map.in-progress',     'In Progress',    false, 'Comma-separated Jira statuses mapped to In Progress'),
    ('roadmap.jira.status-map.qa',              'In Review,QA,Testing', false, 'Comma-separated Jira statuses mapped to QA'),
    ('roadmap.jira.status-map.closed',          'Done,Closed,Resolved', false, 'Comma-separated Jira statuses mapped to Closed'),
    ('roadmap.delivery.readiness-threshold',    '70',             false, 'Minimum aggregate score (0-100) for an item to be marked Ready for Delivery Team'),
    ('roadmap.delivery.complexity-weight-enabled', 'true',        false, 'When true, child scores are weighted by complexity score when rolling up to parent'),
    ('roadmap.review.max-jobs-per-review-all',  '50',             false, 'Maximum number of review jobs enqueued per review-all call (prevents queue flooding)')
ON CONFLICT (key) DO NOTHING;
