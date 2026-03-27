INSERT INTO agent_settings (key, value, is_secret, description) VALUES
  -- Per-category concurrency
  ('job.concurrency.chat',        '10', false, 'Max concurrent CHAT jobs'),
  ('job.concurrency.interactive', '10', false, 'Max concurrent REPLY / FIX_COMMENT / HOOK jobs'),
  ('job.concurrency.pr-work',      '8', false, 'Max concurrent REVIEW / FIX_PR / FIX jobs'),
  ('job.concurrency.background',   '5', false, 'Max concurrent METRICS / QUALITY_REPORT / SYNC_CONFLUENCE / GENERATE_TESTS / GENERATE_DOCS jobs'),
  ('job.concurrency.roadmap',     '20', false, 'Max concurrent REVIEW_EPIC / REVIEW_FEATURE / REVIEW_USERSTORY jobs'),
  -- Per-type priority overrides (1-100, higher = first)
  ('job.priority.chat',             '100', false, 'Dispatch priority for CHAT jobs'),
  ('job.priority.reply',             '80', false, 'Dispatch priority for REPLY jobs'),
  ('job.priority.fix_comment',       '75', false, 'Dispatch priority for FIX_COMMENT jobs'),
  ('job.priority.review',            '70', false, 'Dispatch priority for REVIEW (PR) jobs'),
  ('job.priority.fix_pr',            '70', false, 'Dispatch priority for FIX_PR jobs'),
  ('job.priority.fix',               '60', false, 'Dispatch priority for FIX jobs'),
  ('job.priority.hook',              '50', false, 'Dispatch priority for HOOK jobs (async - caller does not wait)'),
  ('job.priority.metrics',           '40', false, 'Dispatch priority for METRICS jobs'),
  ('job.priority.quality_report',    '35', false, 'Dispatch priority for QUALITY_REPORT jobs'),
  ('job.priority.sync_confluence',   '30', false, 'Dispatch priority for SYNC_CONFLUENCE jobs'),
  ('job.priority.generate_tests',    '25', false, 'Dispatch priority for GENERATE_TESTS jobs'),
  ('job.priority.generate_docs',     '20', false, 'Dispatch priority for GENERATE_DOCS jobs'),
  ('job.priority.review_epic',       '15', false, 'Dispatch priority for REVIEW_EPIC jobs'),
  ('job.priority.review_feature',    '15', false, 'Dispatch priority for REVIEW_FEATURE jobs'),
  ('job.priority.review_userstory',  '15', false, 'Dispatch priority for REVIEW_USERSTORY jobs'),
  -- Roadmap review queue
  ('roadmap.review.refill-batch-size', '10', false, 'Number of roadmap review jobs submitted to in-memory queue per scheduler tick')
ON CONFLICT (key) DO NOTHING;
