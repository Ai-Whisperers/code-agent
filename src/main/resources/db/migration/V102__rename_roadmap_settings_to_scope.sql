-- Rename roadmap.* setting keys to scope.* in agent_settings.
-- Existing overrides are preserved; rows that were never written (using defaults) are unaffected.

UPDATE agent_settings SET key = 'scope.review.model'                       WHERE key = 'roadmap.review.model';
UPDATE agent_settings SET key = 'scope.review.max-tokens'                  WHERE key = 'roadmap.review.max-tokens';
UPDATE agent_settings SET key = 'scope.jira.epic-issuetype'                WHERE key = 'roadmap.jira.epic-issuetype';
UPDATE agent_settings SET key = 'scope.jira.feature-issuetype'             WHERE key = 'roadmap.jira.feature-issuetype';
UPDATE agent_settings SET key = 'scope.jira.userstory-issuetype'           WHERE key = 'roadmap.jira.userstory-issuetype';
UPDATE agent_settings SET key = 'scope.jira.status-map.new'                WHERE key = 'roadmap.jira.status-map.new';
UPDATE agent_settings SET key = 'scope.jira.status-map.in-progress'        WHERE key = 'roadmap.jira.status-map.in-progress';
UPDATE agent_settings SET key = 'scope.jira.status-map.qa'                 WHERE key = 'roadmap.jira.status-map.qa';
UPDATE agent_settings SET key = 'scope.jira.status-map.closed'             WHERE key = 'roadmap.jira.status-map.closed';
UPDATE agent_settings SET key = 'scope.delivery.readiness-threshold'       WHERE key = 'roadmap.delivery.readiness-threshold';
UPDATE agent_settings SET key = 'scope.delivery.complexity-weight-enabled' WHERE key = 'roadmap.delivery.complexity-weight-enabled';
UPDATE agent_settings SET key = 'scope.review.refill-batch-size'           WHERE key = 'roadmap.review.refill-batch-size';

-- roadmap.review.max-jobs-per-review-all had no scope equivalent; leave it under the old key
-- or rename it too for consistency:
UPDATE agent_settings SET key = 'scope.review.max-jobs-per-review-all'    WHERE key = 'roadmap.review.max-jobs-per-review-all';
