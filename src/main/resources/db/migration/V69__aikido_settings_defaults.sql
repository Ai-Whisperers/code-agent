INSERT INTO agent_settings (key, value, is_secret, description) VALUES
  ('aikido.base.url',              'https://app.aikido.dev', false, 'Aikido API base URL'),
  ('aikido.client.id',             '',                       false, 'Aikido OAuth 2.0 client ID for API access'),
  ('aikido.client.secret',         '',                       true,  'Aikido OAuth 2.0 client secret for API access'),
  ('aikido.jira.default-project',  '',                       false, 'Fallback Jira project key for Aikido-triggered Bug tickets'),
  ('jira.transition.in-progress',  '',                       false, 'Jira transition ID to move ticket to In Progress'),
  ('soc2.sla.critical-days',       '5',                      false, 'Maximum calendar days from Jira ticket creation to merge for Critical priority bugs'),
  ('soc2.sla.high-days',           '20',                     false, 'Maximum calendar days from Jira ticket creation to merge for High priority bugs')
ON CONFLICT (key) DO NOTHING;
