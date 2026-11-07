-- V70__add_datev_export_log.sql
CREATE TABLE datev_export_log (
    id                  BIGSERIAL PRIMARY KEY,
    export_type         VARCHAR(32)  NOT NULL,
    period_start        TIMESTAMPTZ  NOT NULL,
    period_end          TIMESTAMPTZ  NOT NULL,
    exported_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    exported_by_user_id UUID         NOT NULL,
    file_hash           VARCHAR(64)  NOT NULL,
    file_name           VARCHAR(255) NOT NULL,
    row_count           INT          NOT NULL,
    entry_count         INT          NOT NULL,
    CONSTRAINT chk_export_type CHECK (export_type IN ('KASSENBUCH', 'AP_LEDGER', 'BUCHUNGSSTAPEL')),
    CONSTRAINT chk_period CHECK (period_end > period_start)
);

CREATE INDEX idx_datev_export_log_type_period
    ON datev_export_log (export_type, period_start, period_end);

CREATE INDEX idx_datev_export_log_exported_at
    ON datev_export_log (exported_at DESC);

COMMENT ON TABLE datev_export_log IS
    'Append-only log of DATEV exports (Kassenbuch / AP-ledger / Buchungsstapel). Never UPDATE or DELETE rows from app code.';
