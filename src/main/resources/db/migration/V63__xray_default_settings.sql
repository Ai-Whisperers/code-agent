INSERT INTO agent_settings (key, value, is_secret, description) VALUES
    ('xray.base-url',   'https://xray.cloud.getxray.app', false, 'Xray Cloud API base URL (US: https://xray.cloud.getxray.app, EU: https://eu.xray.cloud.getxray.app)'),
    ('xray.client-id',  '',                               false, 'Xray Cloud OAuth2 client ID (from Xray API Keys page)'),
    ('xray.client-secret', '',                            true,  'Xray Cloud OAuth2 client secret (stored encrypted)')
ON CONFLICT (key) DO NOTHING;
