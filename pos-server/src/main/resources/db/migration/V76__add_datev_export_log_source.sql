-- V76__add_datev_export_log_source.sql
-- Distinguish auto/scheduled exports from manual user-triggered ones.
-- Existing rows are backfilled as MANUAL because the only export path in
-- production so far has been the Monthly Close buttons, which were meant to
-- be ad-hoc; the catchup detection should not treat them as fulfilled auto
-- periods.

ALTER TABLE datev_export_log
    ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT 'MANUAL';

-- Drop the default so future writers must specify explicitly.
ALTER TABLE datev_export_log
    ALTER COLUMN source DROP DEFAULT;

ALTER TABLE datev_export_log
    ADD CONSTRAINT chk_export_source CHECK (source IN ('AUTO', 'MANUAL'));

COMMENT ON COLUMN datev_export_log.source IS
    'AUTO = produced by the cadence-driven scheduled job (counts toward catchup coverage). MANUAL = produced by an ad-hoc Monthly Close export (audit-only, ignored by catchup detection).';

CREATE INDEX idx_datev_export_log_source_type_period
    ON datev_export_log (source, export_type, period_start, period_end);
