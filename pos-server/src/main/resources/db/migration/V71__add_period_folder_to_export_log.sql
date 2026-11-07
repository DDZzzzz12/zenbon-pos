-- V71__add_period_folder_to_export_log.sql
ALTER TABLE datev_export_log
    ADD COLUMN period_folder VARCHAR(64) NOT NULL DEFAULT '';

COMMENT ON COLUMN datev_export_log.period_folder IS
    'Subfolder name under exportPath where the file lives (e.g. 2026-06, 2026-W23, 2026-Q2). Empty for backfill rows; required for new rows.';
