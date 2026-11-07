-- V72__rewrite_scheduled_jobs_and_config.sql
-- 1. Wipe user-defined scheduled jobs; the BuiltInJobsSeeder will repopulate on next startup.
--    Tables are created at runtime by Exposed (SchemaUtils.create), which runs AFTER Flyway,
--    so guard against missing relations on a fresh install.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = 'public' AND table_name = 'scheduled_job_executions') THEN
        DELETE FROM scheduled_job_executions;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = 'public' AND table_name = 'scheduled_jobs') THEN
        DELETE FROM scheduled_jobs;
    END IF;
END $$;

-- 2. Extend BACKUP_CONFIG with auto-backup fields.
UPDATE shop_configuration
SET value = jsonb_set(
    jsonb_set(
        jsonb_set(
            jsonb_set(
                COALESCE(value, '{}'::jsonb),
                '{autoEnabled}', 'false'::jsonb, true
            ),
            '{cadence}', '"DAILY"'::jsonb, true
        ),
        '{retentionCount}', '7'::jsonb, true
    ),
    '{runHour}', '3'::jsonb, true
)
WHERE key = 'backup_config';

-- 3. Reshape DATEV_XML_CONFIG: drop datevExportCadence, add per-type cadences + runHour.
UPDATE shop_configuration
SET value = (
    (COALESCE(value, '{}'::jsonb)
     - 'datevExportCadence')
    || jsonb_build_object(
        'kassenbuchCadence',
            CASE
                WHEN value->>'datevExportCadence' IN ('MONTHLY','QUARTERLY','YEARLY')
                    THEN 'MONTHLY'
                ELSE 'OFF'
            END,
        'apLedgerCadence', 'OFF',
        'buchungsstapelCadence', 'OFF',
        'runHour', 4
    )
)
WHERE key = 'datev_xml_config';
