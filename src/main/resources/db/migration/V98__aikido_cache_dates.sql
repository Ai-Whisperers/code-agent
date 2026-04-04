-- Add discovery and remediation deadline columns to the Aikido group detail cache.
-- These come from the /issues/export endpoint (first_detected_at, sla_remediate_by)
-- and are not available from the detail endpoint, so they are stored separately.
--
-- Uses ADD COLUMN IF NOT EXISTS so this is safe to run against both:
--   • a fresh database (V97 just created the table)
--   • an existing production database that already has V97 applied

ALTER TABLE aikido_group_detail_cache
    ADD COLUMN IF NOT EXISTS first_detected_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sla_remediate_by   TIMESTAMPTZ;

COMMENT ON COLUMN aikido_group_detail_cache.first_detected_at IS
    'When Aikido first detected this vulnerability (from export field first_detected_at, epoch seconds).';

COMMENT ON COLUMN aikido_group_detail_cache.sla_remediate_by IS
    'Aikido remediation deadline (from export field sla_remediate_by, epoch seconds). NULL when no SLA is set.';

CREATE INDEX IF NOT EXISTS idx_agdc_sla_remediate_by
    ON aikido_group_detail_cache (sla_remediate_by)
    WHERE sla_remediate_by IS NOT NULL;
