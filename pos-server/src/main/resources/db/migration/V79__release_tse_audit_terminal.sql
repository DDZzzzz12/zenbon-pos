-- ===========================================================================
-- V79__release_tse_audit_terminal.sql
--
-- CONSOLIDATED migration (2026-08-02). Replaces the 11 per-feature files listed
-- below; content is byte-identical, concatenated in version order. The original
-- per-file header comments are preserved inline so provenance stays traceable.
--
-- Flyway note: this consolidation CHANGES migration checksums. Fresh installs and
-- CI (embedded zonky) are unaffected; any pre-existing DB that already applied the
-- old V{N} files must run `flyway repair` / re-baseline once (see docs).
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- [source: V98__widen_tse_process_data.sql]
-- ---------------------------------------------------------------------------
-- B1 (isolated audit 2026-07-22): process_data_hash stores the EXACT processData handed to the
-- TSE, but was varchar(500).
--
-- A Bestellung-V1 payload is one CSV line per order line (Anhang I p. 116) and a Kassenbeleg's
-- <Zahlungen> section grows with split tenders, so both are unbounded; roughly 14 order lines
-- exceeded 500 characters. The TSE had ALREADY SIGNED by the time the INSERT raised SQLSTATE
-- 22001, so the overflow produced a signature in the WORM log with no database row, no DSFinV-K
-- export row, an order stranded PENDING, and a queue entry that re-signed the same payload on
-- the device every 60 seconds indefinitely.
--
-- TEXT removes the ceiling entirely and matches public_key / certificate, which are already
-- unbounded for the same reason. Widening is non-destructive: every existing value fits.
ALTER TABLE tse_transaction ALTER COLUMN process_data_hash TYPE TEXT;

-- ---------------------------------------------------------------------------
-- [source: V99__cancellation_batch_id.sql]
-- ---------------------------------------------------------------------------
-- M2 (isolated audit 2026-07-22): group the rows created by ONE cancel action into ONE DSFinV-K
-- Vorgang.
--
-- `cancellation` is a per-item table, so cancelling N items produced N rows and the export emitted
-- N Vorgaenge (C-1, C-2, ...) -- while the TSE signed ONCE over a payload covering all N lines.
-- The signature attested to the aggregate, so no individual Beleg could be verified against it and
-- an auditor saw one transaction number claimed by N Vorgaenge.
--
-- An order of N items already works the right way: one `orders` row, one AVBestellung Vorgang, one
-- signature covering all its lines. This column lets cancellations be modelled identically.
--
-- Nullable on purpose: rows written before this column existed keep NULL, and the export falls back
-- to grouping those by their shared tse_transaction_id, which was the de-facto batch key.
ALTER TABLE cancellation ADD COLUMN batch_id VARCHAR(36);

-- Backfill legacy rows from the shared TSE transaction so historical exports group correctly too.
UPDATE cancellation
   SET batch_id = 'tx' || tse_transaction_id
 WHERE batch_id IS NULL
   AND tse_transaction_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cancellation_batch_id ON cancellation (batch_id);

-- ---------------------------------------------------------------------------
-- [source: V100__orders_zbon_link.sql]
-- ---------------------------------------------------------------------------
-- Orders were the only fiscal source without a recorded Kassenabschluss. `bill`, `cancellation`
-- and `kassenbuch_header` all carry zbon_id and the export resolves them by that link FIRST;
-- orders were attributed purely by window arithmetic at export time:
--
--   Orders.find { orderTime between min(zbon.startTime)..max(zbon.endTime) }
--         .filter { closingFor(zBons, it.orderTime) != null }
--
-- So an order lying in a GAP between two closings -- the shop failed to close a day, or a closing
-- failed -- was inside the query window but covered by none, and was DROPPED from the export
-- without a trace. Those orders were TSE-signed as AVBestellung, so the TSE log held signatures
-- the DSFinV-K export could not explain (Tz. 2.1).
ALTER TABLE t_order ADD COLUMN zbon_id BIGINT NULL REFERENCES zbon(zbon_id);

CREATE INDEX IF NOT EXISTS idx_t_order_zbon_id ON t_order (zbon_id);

-- Backfill from the covering closing so existing exports keep the attribution they already had.
-- Orders in a gap stay NULL and are claimed by the next closing that runs.
UPDATE t_order o
   SET zbon_id = (
        SELECT z.zbon_id FROM zbon z
         WHERE o.order_time >= z.start_time
           AND o.order_time <= z.end_time
         ORDER BY z.zbon_id
         LIMIT 1
   )
 WHERE o.zbon_id IS NULL;

-- ---------------------------------------------------------------------------
-- [source: V101__tse_transaction_reference.sql]
-- ---------------------------------------------------------------------------
-- M7 (isolated audit 2026-07-22): a TSE transaction recorded no link to the Vorgang it signed.
--
-- Consequences: the TSE log could not be explained per Vorgang during a TAR-vs-DSFinV-K
-- reconciliation, and a signature that persisted while its entity link did NOT (duplicate enqueue,
-- crash between the write and the link) was indistinguishable from "never signed" -- so the
-- Nachsignieren queue signed the same payload a SECOND time, permanently, into WORM storage.
--
-- This does not close the lost-response window (nothing is written at all when the response never
-- arrives); that case is handled by recording the double-sign RISK in the audit log instead.
ALTER TABLE tse_transaction ADD COLUMN reference_id VARCHAR(100);
ALTER TABLE tse_transaction ADD COLUMN entity_type VARCHAR(30);

CREATE INDEX IF NOT EXISTS idx_tse_transaction_reference
    ON tse_transaction (reference_id, entity_type);

-- ---------------------------------------------------------------------------
-- [source: V102__tse_transaction_log_time_format.sql]
-- ---------------------------------------------------------------------------
-- m3 (isolated audit 2026-07-22): TSE_ZEITFORMAT in Stamm_TSE was hardcoded "unixTime".
--
-- TSE_ZEITFORMAT (Anhang E p. 28) documents the TSE's INTERNAL log-time format — one of five legal
-- values (unixTime, utcTime, utcTimeWithSeconds, generalizedTime, ...). It is a property OF THE
-- DEVICE, not of the export run: a DSFinV-K export re-derives historical closings, so a live device
-- read cannot answer for a row a since-swapped TSE signed. The value is therefore persisted per
-- transaction, exactly like signature_algorithm.
--
-- Truthful for the Swissbit WORM in use (unixTime); the export falls back to "unixTime" for any row
-- predating this column, which is the correct value for that device's historical rows.
ALTER TABLE tse_transaction ADD COLUMN log_time_format VARCHAR(31);

-- ---------------------------------------------------------------------------
-- [source: V103__strip_tse_admin_credentials.sql]
-- ---------------------------------------------------------------------------
-- Remove the stored TSE Admin PIN, Admin PUK and credential seed.
--
-- The Swissbit guidance manual (2.5) forbids the ERS from storing the Admin PIN or PUK; 2.4
-- explicitly permits the TimeAdmin PIN, which is why `timeAdminPin` stays. Nothing unattended
-- needs the Admin credentials: transaction signing authenticates no user at all, the self test
-- requires no login, and time sync runs as TimeAdmin. They are now supplied per sitting by an
-- operator and held in memory only (TseAdminSession).
--
-- The credential seed goes too — it is single-use, consumed by the setup call it is sent for,
-- so a stored copy is spent and worthless while still being a secret sitting in a table.
--
-- Written as a JSONB key removal rather than a row delete: the rest of the TSE configuration
-- (mode, ports, keep-alive, failure behaviour) must survive untouched.

UPDATE shop_configuration
SET value = value - 'adminPin' - 'adminPuk' - 'credentialSeed'
WHERE key = 'tse_config'
  AND value IS NOT NULL
  AND jsonb_typeof(value) = 'object';

-- ---------------------------------------------------------------------------
-- [source: V104__terminal_identity_and_pairing.sql]
-- ---------------------------------------------------------------------------
-- Terminal identity, pairing & roles — data layer (Phase A1).
-- Existing TSE clients are left untouched: rows keep terminal_id + tse_client_registered.

-- 1) device_id becomes nullable (unbound terminals have no device).
ALTER TABLE terminals ALTER COLUMN device_id DROP NOT NULL;

-- 2) New identity/binding/role columns.
ALTER TABLE terminals ADD COLUMN name VARCHAR(100);
ALTER TABLE terminals ADD COLUMN role VARCHAR(40) NOT NULL DEFAULT 'CASHIER';
ALTER TABLE terminals ADD COLUMN fiscal BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE terminals ADD COLUMN device_token_hash VARCHAR(128);
ALTER TABLE terminals ADD COLUMN device_token_expires_at TIMESTAMP;
ALTER TABLE terminals ADD COLUMN bound_at TIMESTAMP;
ALTER TABLE terminals ADD COLUMN location VARCHAR(150);
ALTER TABLE terminals ADD COLUMN display_config TEXT;
ALTER TABLE terminals ADD COLUMN client_kind VARCHAR(10) NOT NULL DEFAULT 'NATIVE';

-- 3) Backfill existing rows as bound fiscal cashier terminals (they were auto-registering TSE clients).
UPDATE terminals SET bound_at = registered_at WHERE device_id IS NOT NULL AND bound_at IS NULL;
UPDATE terminals SET name = terminal_id WHERE name IS NULL;

-- 3b) De-duplicate any legacy terminal_id collisions (old auto-create race) before the unique index.
--     The old auto-create path (max+1, no locking) could assign the same TER-00N to two devices;
--     keep the earliest row's terminal_id and rename all later duplicates so the index below can be created.
UPDATE terminals t
SET terminal_id = t.terminal_id || '-dup' || t.id
WHERE EXISTS (SELECT 1 FROM terminals t2 WHERE t2.terminal_id = t.terminal_id AND t2.id < t.id);

-- 4) terminal_id (clientId) becomes unique.
CREATE UNIQUE INDEX IF NOT EXISTS uq_terminals_terminal_id ON terminals(terminal_id);

-- 5) Pairing codes table.
CREATE TABLE terminal_pairing_codes (
    id                  BIGSERIAL PRIMARY KEY,
    terminal_id         BIGINT NOT NULL REFERENCES terminals(id),
    code                VARCHAR(12) NOT NULL,
    expires_at          TIMESTAMP NOT NULL,
    consumed_at         TIMESTAMP,
    admin_session_epoch BIGINT,
    -- F42, folded in before v1.1.0: pairing with this code registers the terminal's TSE client
    -- only when the operator chose it when issuing the code. FALSE = pair without touching the TSE.
    register_tse_client BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP NOT NULL
);
CREATE INDEX idx_terminal_pairing_codes_code ON terminal_pairing_codes(code);

-- 6) Ensure exactly one shared terminal-device employee: reuse an existing terminal-role one if
--    present, otherwise seed a fresh account. Idempotent.
--    employees NOT-NULL columns without a DB-level default (derived from EmployeeTable.kt):
--    employee_id (PK, UUID), username, password_hash, employee_number, role, created_at, updated_at.
--    (employment_status/language_preference/training_mode/in_rotation_pool all have real DB defaults
--    via Exposed .default(...) and are omitted here.)
-- 6a) Reuse an existing terminal-role employee as the shared 'terminal-device' account when one is
--     present (role is still 'TERMINAL' here — V105 renames it to 'TERMINAL_DEVICE' next). Without
--     this the INSERT below would add a SECOND row and V105 would then rename the legacy one too,
--     leaving two TERMINAL_DEVICE employees. TERMINAL/TERMINAL_DEVICE is a device role (not a person),
--     so normalizing its username/number is safe. Promotes the earliest match, only while the
--     canonical username is still free.
UPDATE employees
SET username = 'terminal-device',
    employee_number = 'TERMINAL-DEVICE',
    role = 'TERMINAL_DEVICE',
    updated_at = NOW()
WHERE employee_id = (
    SELECT employee_id FROM employees
    WHERE role IN ('TERMINAL', 'TERMINAL_DEVICE')
    ORDER BY created_at
    LIMIT 1
)
AND NOT EXISTS (SELECT 1 FROM employees WHERE username = 'terminal-device');

-- 6b) No terminal-role employee to reuse → seed a fresh shared terminal-device account.
INSERT INTO employees (employee_id, username, password_hash, employee_number, role, created_at, updated_at)
SELECT gen_random_uuid(), 'terminal-device', '', 'TERMINAL-DEVICE', 'TERMINAL_DEVICE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM employees WHERE username = 'terminal-device');

-- ---------------------------------------------------------------------------
-- [source: V105__rename_terminal_role_to_terminal_device.sql]
-- ---------------------------------------------------------------------------
-- Reuse the existing TERMINAL role as the terminal-device principal: rename stored role values.
UPDATE employees SET role = 'TERMINAL_DEVICE' WHERE role = 'TERMINAL';

-- ---------------------------------------------------------------------------
-- [source: V106__kassenbuch_transit_outbox.sql]
-- ---------------------------------------------------------------------------
-- Transactional outbox for Geldtransit advices owed to POS-ERP's cash ledger.
--
-- Written in the same transaction as the Kassenbuch header it describes, so the announcement
-- survives a crash between commit and delivery. Drained by TransitAdviceSweeper.
--
-- announced_amount is ALREADY SIGN-INVERTED relative to the Kassenbuch entry: positive means
-- cash arrives at the destination ledger. The two legs are the same movement seen from
-- opposite ends.
CREATE TABLE kassenbuch_transit_outbox (
    id                  BIGSERIAL PRIMARY KEY,
    header_id           BIGINT         NOT NULL REFERENCES kassenbuch_header (id),
    source_urn          VARCHAR(300)   NOT NULL,
    announced_amount    DECIMAL(19, 4) NOT NULL,
    announced_at        TIMESTAMP      NOT NULL,
    target_ledger_code  VARCHAR(30)    NOT NULL,
    status              VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    retry_count         INTEGER        NOT NULL DEFAULT 0,
    max_retries         INTEGER        NOT NULL DEFAULT 10,
    last_error          TEXT,
    last_error_code     VARCHAR(50),
    created_at          TIMESTAMP      NOT NULL,
    last_attempt_at     TIMESTAMP,
    delivered_at        TIMESTAMP,
    next_retry_at       TIMESTAMP
);

-- One entry yields at most one advice: the idempotency backbone of the whole pipeline.
-- A duplicate enqueue, a retried create or a replayed transaction all collapse onto one row.
CREATE UNIQUE INDEX uq_transit_outbox_header ON kassenbuch_transit_outbox (header_id);
CREATE UNIQUE INDEX uq_transit_outbox_source_urn ON kassenbuch_transit_outbox (source_urn);

-- The sweeper claims rows by (status, next_retry_at).
CREATE INDEX idx_transit_outbox_status ON kassenbuch_transit_outbox (status);
CREATE INDEX idx_transit_outbox_next_retry ON kassenbuch_transit_outbox (next_retry_at);

-- ---------------------------------------------------------------------------
-- [source: V107__create_changelog_release_table.sql]
-- ---------------------------------------------------------------------------
-- Release-notes table. The `changelog_release` table is the single source of truth
-- for user-facing release notes; the previously-existing root CHANGELOG.md was
-- deleted (spec 2026-08-02-changelog-release-notes-table-design.md).
--
-- Migration-only writes: every subsequent release adds a V{N}__release_x_y_z.sql
-- migration that INSERTs one row carrying full EN/DE/ZH translations of title,
-- summary, highlights bullets and known-issues bullets.
--
-- Application code is READ-ONLY against this table.
--
-- Release notes are app-global by design — every shop on this pos-server sees the
-- same notes; this departs from the per-shop pattern used elsewhere in this DB.
-- No shop_id column; no shop-isolation filter at the API layer.

CREATE TABLE changelog_release (
    release_version     TEXT        NOT NULL,
    release_date        TIMESTAMP   NOT NULL DEFAULT NOW(),
    title_en            TEXT        NOT NULL,
    title_de            TEXT        NOT NULL,
    title_zh            TEXT        NOT NULL,
    summary_en          TEXT        NOT NULL,
    summary_de          TEXT        NOT NULL,
    summary_zh          TEXT        NOT NULL,
    highlights_en       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    highlights_de       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    highlights_zh       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    known_issues_en     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    known_issues_de     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    known_issues_zh     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    applied_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    applied_by          TEXT        NOT NULL DEFAULT current_user,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (release_version)
);

-- ---------------------------------------------------------------------------
-- [source: V109__zbon_promotion_breakdown.sql]
-- ---------------------------------------------------------------------------
-- Adds per-promotion breakdown to ZBon reports.
-- Stores aggregated count + total per promotion so the ZBon UI/PDF/print
-- can show "2x Happy Hour 20% = €15.40" per day.

ALTER TABLE zbon
    ADD COLUMN total_promotions DECIMAL(19, 4) NOT NULL DEFAULT 0.0000;

CREATE TABLE zbon_promotion_summary (
    id               BIGSERIAL PRIMARY KEY,
    zbon_id           BIGINT                   NOT NULL REFERENCES zbon(zbon_id) ON DELETE CASCADE,
    promotion_id     BIGINT                   NOT NULL,
    promotion_name   VARCHAR(200)              NOT NULL,
    discount_type    VARCHAR(20)               NOT NULL,
    times_applied    INTEGER                  NOT NULL DEFAULT 0,
    total_deducted   DECIMAL(19, 4)            NOT NULL DEFAULT 0.0000,
    created_at       TIMESTAMP                NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_zbon_promotion_zbon_id ON zbon_promotion_summary(zbon_id);

