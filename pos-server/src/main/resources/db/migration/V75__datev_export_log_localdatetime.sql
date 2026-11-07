-- V75__datev_export_log_localdatetime.sql
--
-- Convert datev_export_log time columns from TIMESTAMPTZ to TIMESTAMP so the
-- table matches the rest of the schema (bill, zbon, kassenbuch, etc.). The
-- USING clause shifts each stored UTC instant back into the shop's configured
-- wall-clock time before stripping the zone, so the literal values written
-- after the migration are equal (modulo precision) to what `currentTimeStamp()`
-- — the LocalDateTime clock the codebase uses — produces.
--
-- If shop_info is empty (initial-setup edge case) we fall back to UTC, which
-- keeps the literal wall-clock unchanged.

DO $$
DECLARE
    shop_zone TEXT;
BEGIN
    SELECT shop_timezone INTO shop_zone FROM shop_info LIMIT 1;
    IF shop_zone IS NULL OR shop_zone = '' THEN
        shop_zone := 'UTC';
    END IF;

    -- Drop the DEFAULT now() on exported_at first; we'll re-add a LocalDateTime-friendly default.
    EXECUTE 'ALTER TABLE datev_export_log ALTER COLUMN exported_at DROP DEFAULT';

    EXECUTE format(
        'ALTER TABLE datev_export_log ' ||
        'ALTER COLUMN period_start TYPE TIMESTAMP USING (period_start AT TIME ZONE %L), ' ||
        'ALTER COLUMN period_end   TYPE TIMESTAMP USING (period_end   AT TIME ZONE %L), ' ||
        'ALTER COLUMN exported_at  TYPE TIMESTAMP USING (exported_at  AT TIME ZONE %L)',
        shop_zone, shop_zone, shop_zone
    );

    EXECUTE format(
        'ALTER TABLE datev_export_log ALTER COLUMN exported_at SET DEFAULT (now() AT TIME ZONE %L)',
        shop_zone
    );
END $$;

COMMENT ON COLUMN datev_export_log.period_start IS 'LocalDateTime in the shop timezone (matches bill / zbon / kassenbuch columns).';
COMMENT ON COLUMN datev_export_log.period_end   IS 'LocalDateTime in the shop timezone.';
COMMENT ON COLUMN datev_export_log.exported_at  IS 'LocalDateTime in the shop timezone when the export was created.';
