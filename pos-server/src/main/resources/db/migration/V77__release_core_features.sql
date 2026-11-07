-- ===========================================================================
-- V77__release_core_features.sql
--
-- CONSOLIDATED migration (2026-08-02). Replaces the 16 per-feature files listed
-- below; content is byte-identical, concatenated in version order. The original
-- per-file header comments are preserved inline so provenance stays traceable.
--
-- Flyway note: this consolidation CHANGES migration checksums. Fresh installs and
-- CI (embedded zonky) are unaffected; any pre-existing DB that already applied the
-- old V{N} files must run `flyway repair` / re-baseline once (see docs).
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- [source: V77__add_refresh_tokens.sql]
-- ---------------------------------------------------------------------------
-- Server-side refresh-token tracking for real logout/revocation.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id               UUID PRIMARY KEY,
    jti              VARCHAR(36)  NOT NULL,
    token_hash       VARCHAR(64)  NOT NULL,
    employee_id      UUID         NOT NULL REFERENCES employees(employee_id),
    device_id        VARCHAR(255) NOT NULL,
    issued_at        TIMESTAMP    NOT NULL,
    expires_at       TIMESTAMP    NOT NULL,
    revoked_at       TIMESTAMP,
    replaced_by_jti  VARCHAR(36),
    last_used_at     TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_refresh_tokens_jti ON refresh_tokens (jti);
CREATE INDEX IF NOT EXISTS ix_refresh_tokens_employee ON refresh_tokens (employee_id);
CREATE INDEX IF NOT EXISTS ix_refresh_tokens_expires ON refresh_tokens (expires_at);

-- ---------------------------------------------------------------------------
-- [source: V78__add_choice_groups.sql]
-- ---------------------------------------------------------------------------
-- V78__add_choice_groups.sql
-- Generalized menus & choice groups: additive schema only.

-- Option becomes generic: optional link to a real product (identity only).
ALTER TABLE product_extras
    ADD COLUMN linked_product_id BIGINT REFERENCES product(id) ON DELETE SET NULL;

-- Reusable selection group.
CREATE TABLE choice_group (
    id              SERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    en_name         VARCHAR(255),
    ch_name         VARCHAR(255),
    min_selections  INTEGER NOT NULL DEFAULT 0,
    max_selections  INTEGER NOT NULL DEFAULT 1,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_choice_group_sel CHECK (min_selections >= 0 AND max_selections >= 1 AND min_selections <= max_selections)
);

-- Option placed in a group, with per-placement price delta.
CREATE TABLE choice_group_option (
    group_id      INTEGER NOT NULL REFERENCES choice_group(id) ON DELETE CASCADE,
    option_id     INTEGER NOT NULL REFERENCES product_extras(id) ON DELETE CASCADE,
    price_delta   DECIMAL(19,4) NOT NULL DEFAULT 0,
    sort_position INTEGER NOT NULL DEFAULT 0,
    is_default    BOOLEAN NOT NULL DEFAULT false,
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (group_id, option_id)
);
CREATE INDEX idx_cgo_group ON choice_group_option(group_id);
CREATE INDEX idx_cgo_option ON choice_group_option(option_id);

-- Group attached to a product.
CREATE TABLE product_choice_group (
    product_id    BIGINT NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    group_id      INTEGER NOT NULL REFERENCES choice_group(id) ON DELETE CASCADE,
    sort_position INTEGER NOT NULL DEFAULT 0,
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (product_id, group_id)
);
CREATE INDEX idx_pcg_product ON product_choice_group(product_id);
CREATE INDEX idx_pcg_group ON product_choice_group(group_id);

-- Restructure item_extras: surrogate key + nullable option FK + snapshot columns + quantity.
ALTER TABLE item_extras ADD COLUMN id BIGSERIAL;
ALTER TABLE item_extras ADD COLUMN option_name_snapshot VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE item_extras ADD COLUMN price_delta_snapshot DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE item_extras ADD COLUMN group_name_snapshot VARCHAR(255);
ALTER TABLE item_extras ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1;

-- Backfill snapshots for existing rows from the live option (best-effort historical value).
UPDATE item_extras ie
SET option_name_snapshot = pe.name,
    price_delta_snapshot  = pe.price
FROM product_extras pe
WHERE ie.product_extras_id = pe.id;

-- Swap primary key from composite to surrogate, make option FK nullable.
-- Drop whatever PRIMARY KEY constraint currently exists on item_extras. The original
-- composite PK was created by Exposed SchemaUtils on fresh DBs, so its generated name is
-- not known here; discover and drop it by type rather than by a hardcoded literal name.
DO $$
DECLARE
    pk_name TEXT;
BEGIN
    SELECT conname INTO pk_name
    FROM pg_constraint
    WHERE conrelid = 'item_extras'::regclass AND contype = 'p';
    IF pk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE item_extras DROP CONSTRAINT %I', pk_name);
    END IF;
END $$;
ALTER TABLE item_extras ALTER COLUMN product_extras_id DROP NOT NULL;
ALTER TABLE item_extras ADD PRIMARY KEY (id);
CREATE INDEX idx_item_extras_order_item ON item_extras(order_item_id);

-- ---------------------------------------------------------------------------
-- [source: V79__migrate_extras_to_addons_group.sql]
-- ---------------------------------------------------------------------------
-- V79__migrate_extras_to_addons_group.sql
-- Auto-migrate legacy product_extras_assignment into a shared "Add-ons" choice group.

-- 1. Create the shared optional group (only if there is anything to migrate).
INSERT INTO choice_group (name, en_name, ch_name, min_selections, max_selections, is_active, updated_at)
SELECT 'Extras', 'Add-ons', '附加', 0, 999, true, NOW()
WHERE EXISTS (SELECT 1 FROM product_extras_assignment);

-- 2. Place every distinct assigned extra into that group, delta = current price.
INSERT INTO choice_group_option (group_id, option_id, price_delta, sort_position, is_default, updated_at)
SELECT cg.id, pe.id, pe.price, 0, false, NOW()
FROM choice_group cg
CROSS JOIN (SELECT DISTINCT extras_id FROM product_extras_assignment) a
JOIN product_extras pe ON pe.id = a.extras_id
WHERE cg.name = 'Extras' AND cg.en_name = 'Add-ons'
ON CONFLICT (group_id, option_id) DO NOTHING;

-- 3. Attach the group to every product that had any assignment.
INSERT INTO product_choice_group (product_id, group_id, sort_position, updated_at)
SELECT DISTINCT pea.product_id, cg.id, 100, NOW()
FROM product_extras_assignment pea
CROSS JOIN choice_group cg
WHERE cg.name = 'Extras' AND cg.en_name = 'Add-ons'
ON CONFLICT (product_id, group_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- [source: V80__changefeed_entrylog_type_rename.sql]
-- ---------------------------------------------------------------------------
-- V80__changefeed_entrylog_type_rename.sql
-- Rename entry_log.type values to the merged ChangeEntityType taxonomy.
-- id values are untouched, so existing client cursors remain valid.
UPDATE entry_log SET type = 'CATEGORY'  WHERE type = 'CATEGORY';
UPDATE entry_log SET type = 'PRODUCT_EXTRA'     WHERE type = 'PRODUCTEXTRAS';
UPDATE entry_log SET type = 'PAYMENT_TYPE'      WHERE type = 'PAYMENTTYPE';
UPDATE entry_log SET type = 'ROOM_ELEMENT'      WHERE type = 'ROOMELEMENT';
UPDATE entry_log SET type = 'SHOP_INFO'         WHERE type = 'SHOP';
UPDATE entry_log SET type = 'PRINTER_LOCATION'  WHERE type = 'PRINTERLOCATION';
-- PRODUCT, RTABLE, PRODUCT_EXTRAS_ASSIGNMENT, VAT, EMPLOYEE, SETUP, TERMINAL,
-- TIME_TRACKING already match the new names and need no change.

-- ---------------------------------------------------------------------------
-- [source: V82__add_kassenbuch_correction_columns.sql]
-- ---------------------------------------------------------------------------
-- Correction (Storno + corrected re-entry) audit columns for kassenbuch_header.
-- corrects_header_id: the original entry this row corrects (null for ordinary entries).
-- correction_role: REVERSAL or RE_ENTRY (null for ordinary entries).
ALTER TABLE kassenbuch_header
    ADD COLUMN corrects_header_id BIGINT NULL REFERENCES kassenbuch_header (id),
    ADD COLUMN correction_role VARCHAR(20) NULL;

CREATE INDEX idx_kassenbuch_header_corrects_header_id
    ON kassenbuch_header (corrects_header_id);

-- ---------------------------------------------------------------------------
-- [source: V83__tse_signing_status_default_pending.sql]
-- ---------------------------------------------------------------------------
-- Defense-in-depth: the resolver now always sets tse_signing_status explicitly, but the
-- column default of 'SIGNED' was a footgun (any insert that omitted it looked signed).
-- Flip the default to 'PENDING' so an unset status is honestly "not yet signed".
ALTER TABLE t_order          ALTER COLUMN tse_signing_status SET DEFAULT 'PENDING';
ALTER TABLE bill             ALTER COLUMN tse_signing_status SET DEFAULT 'PENDING';
ALTER TABLE cancellation     ALTER COLUMN tse_signing_status SET DEFAULT 'PENDING';
ALTER TABLE kassenbuch_header ALTER COLUMN tse_signing_status SET DEFAULT 'PENDING';

-- ---------------------------------------------------------------------------
-- [source: V84__order_item_query_indexes.sql]
-- ---------------------------------------------------------------------------
-- Performance indexes for order-item / order query hot paths.
--
-- t_order_item and t_order are queried by foreign-key / filter columns that had
-- no supporting index, forcing sequential scans that degrade as the table grows
-- past ~1M rows. PostgreSQL does not auto-create indexes on FK columns, so we add
-- them explicitly here.
--
-- Index names match the Exposed index() declarations on the corresponding tables
-- (OrderItemTable, OrdersTable, CancellationTable) so that fresh installs (schema
-- created by Exposed, this migration baselined-as-applied) and existing installs
-- (this migration runs, then SchemaUtils.create() is a no-op) converge on the same
-- index set. IF NOT EXISTS keeps both paths idempotent.
--
-- Migrations run at startup before the server accepts traffic, so a plain
-- (non-CONCURRENT) CREATE INDEX is fine — the brief build-time lock is invisible.

-- Hot path: open items for a table session (getItemForTableSession).
CREATE INDEX IF NOT EXISTS idx_order_item_session_status
    ON t_order_item (table_session_id, item_status);

-- FK lookups used while joining / mapping order items.
CREATE INDEX IF NOT EXISTS idx_order_item_orders_id  ON t_order_item (orders_id);
CREATE INDEX IF NOT EXISTS idx_order_item_product_id ON t_order_item (product_id);
CREATE INDEX IF NOT EXISTS idx_order_item_bill_id    ON t_order_item (bill_id);

-- Cancellation reporting by date range (getCancellations).
CREATE INDEX IF NOT EXISTS idx_cancellation_created_at ON cancellation (created_at);

-- Order FK filters (findByTableSessionId / findByEmployeeId).
CREATE INDEX IF NOT EXISTS idx_order_table_session ON t_order (table_session_id);
CREATE INDEX IF NOT EXISTS idx_order_employee      ON t_order (employee_id);

-- ---------------------------------------------------------------------------
-- [source: V85__tse_transaction_per_device_unique.sql]
-- ---------------------------------------------------------------------------
-- Fix TSE transaction uniqueness: per-device, not global.
--
-- The original schema declared a GLOBAL unique index on tse_transaction.transaction_number
-- (Exposed-generated name: tse_transaction_transaction_number_unique). But a TSE transaction
-- number is a PER-DEVICE counter: it restarts at 1 whenever the TSE is replaced/re-initialized,
-- and SIMULATION mode emits epoch-millis values under a constant serial. The global unique
-- therefore wrongly rejected valid receipts from a new/replacement TSE whose counter (1, 2, 3…)
-- collided with rows left by the previous device. Symptom:
--   ERROR: duplicate key value violates unique constraint "tse_transaction_transaction_number_unique"
--   Detail: Key (transaction_number)=(3) already exists.
--
-- Correct uniqueness is the composite (tse_serial_number, transaction_number): each device owns
-- its own number sequence; the same number across two distinct serials is legitimate; a true
-- duplicate within one device is still rejected.
--
-- Index name matches the Exposed uniqueIndex() declaration on TseTransactionTable so fresh
-- installs (schema created by Exposed) and existing installs (this migration runs, then
-- SchemaUtils.create() is a no-op) converge on the same index set. Exposed creates the old
-- index as a unique INDEX; drop it as both index and constraint to be safe across versions.

ALTER TABLE tse_transaction DROP CONSTRAINT IF EXISTS tse_transaction_transaction_number_unique;
DROP INDEX IF EXISTS tse_transaction_transaction_number_unique;

CREATE UNIQUE INDEX IF NOT EXISTS tse_transaction_serial_number_unique
    ON tse_transaction (tse_serial_number, transaction_number);

-- ---------------------------------------------------------------------------
-- [source: V86__hot_path_query_indexes.sql]
-- ---------------------------------------------------------------------------
-- Performance indexes for hot-path queries on bill / payment / session / audit /
-- TSE / card / product tables.
--
-- These tables are queried by foreign-key and filter columns that had no supporting
-- index, forcing sequential scans that degrade as the tables grow. PostgreSQL does
-- not auto-create indexes on FK columns, so we add them explicitly here.
--
-- Index names match the Exposed index() declarations on the corresponding tables
-- (BillTable, PaymentTable, TableSessionTable, EntryLogTable, TseSigningQueueTable,
-- TseTransactionTable, CardTransactionTable, CardSettlementTable, ProductTable) so
-- that fresh installs (schema created by Exposed, this migration baselined-as-applied)
-- and existing installs (this migration runs, then SchemaUtils.create() is a no-op)
-- converge on the same index set. IF NOT EXISTS keeps both paths idempotent.
--
-- Migrations run at startup before the server accepts traffic, so a plain
-- (non-CONCURRENT) CREATE INDEX is fine — the brief build-time lock is invisible.

-- Bill reporting by date range (ZBon, statistics, Kassenbuch) and per-employee reports.
CREATE INDEX IF NOT EXISTS idx_bill_end_time          ON bill (end_time);
CREATE INDEX IF NOT EXISTS idx_bill_employee_end_time ON bill (employee_id, end_time);

-- Bill FK lookups (bills of a session, ZBon assignment, storno chain).
CREATE INDEX IF NOT EXISTS idx_bill_table_session   ON bill (table_session);
CREATE INDEX IF NOT EXISTS idx_bill_zbon_id         ON bill (zbon_id);
CREATE INDEX IF NOT EXISTS idx_bill_deleted_bill_id ON bill (deleted_bill_id);

-- Payment FK lookups (payments of a bill, per-payment-type reporting, refund chain).
CREATE INDEX IF NOT EXISTS idx_payment_bill_id          ON payment (bill_id);
CREATE INDEX IF NOT EXISTS idx_payment_payment_type_id  ON payment (payment_type_id);
CREATE INDEX IF NOT EXISTS idx_payment_refunded_bill_id ON payment (refunded_bill_id);

-- Hot path: active (non-completed) session for a table.
CREATE INDEX IF NOT EXISTS idx_table_session_table_completed ON table_session (table_id, completed);

-- Session FK / range filters (per-employee sessions, date-range reporting).
CREATE INDEX IF NOT EXISTS idx_table_session_employee   ON table_session (employee_id);
CREATE INDEX IF NOT EXISTS idx_table_session_start_time ON table_session (start_time);

-- Entry log: paginated audit views filter by type/operation with a created_at range
-- and order by created_at; entity_id drives the per-entity history view.
CREATE INDEX IF NOT EXISTS idx_entry_log_created_at      ON entry_log (created_at);
CREATE INDEX IF NOT EXISTS idx_entry_log_type_op_created ON entry_log (type, operation, created_at);
CREATE INDEX IF NOT EXISTS idx_entry_log_entity_id       ON entry_log (entity_id);

-- TSE signing queue: the background poller repeatedly scans for PENDING/RETRY
-- entries whose next_retry_at is due. tse_signing_queue is created by Exposed only
-- (no Flyway CREATE TABLE), so guard against upgrades where this migration runs
-- before SchemaUtils.create() has ever created the table; Exposed then creates the
-- table together with this index in the same startup.
DO $$
BEGIN
    IF to_regclass('tse_signing_queue') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_tse_queue_status_retry
            ON tse_signing_queue (signing_status, next_retry_at);
    END IF;
END $$;

-- TSE transaction lookups by date range (DSFinV-K export, financial docs).
CREATE INDEX IF NOT EXISTS idx_tse_tx_start_time ON tse_transaction (start_time);

-- Card transaction FK lookup and per-terminal date-range queries (settlement matching).
CREATE INDEX IF NOT EXISTS idx_card_tx_payment_id    ON card_transaction (payment_id);
CREATE INDEX IF NOT EXISTS idx_card_tx_terminal_time ON card_transaction (card_terminal_id, transaction_time);

-- Card settlement FK lookups (per-terminal history, ZBon linkage).
CREATE INDEX IF NOT EXISTS idx_card_settlement_terminal ON card_settlement (card_terminal_id);
CREATE INDEX IF NOT EXISTS idx_card_settlement_zbon     ON card_settlement (zbon_id);

-- Product listing: active products of a category (menu / POS grid).
CREATE INDEX IF NOT EXISTS idx_product_category_active ON product (category_id, is_active);

-- ---------------------------------------------------------------------------
-- [source: Zahlartwechsel as Storno + Neubuchung, 2026-09-29]
-- ---------------------------------------------------------------------------
-- A Zahlartwechsel stornos the original bill (storno_reason = 'ZAHLARTWECHSEL' on the Storno Beleg)
-- and books a new bill from the same items; the new bill points at the original it replaces.
ALTER TABLE bill ADD COLUMN IF NOT EXISTS storno_reason VARCHAR(32) NULL;

-- The constraint is named explicitly, and both actions are spelled out, because that is what
-- Exposed generates for `reference("rebooks_bill_id", BillTable).nullable()`. Left to PostgreSQL
-- the constraint would be `bill_rebooks_bill_id_fkey` with no stated ON UPDATE, and
-- SchemaFlywayParityTest would (rightly) report the Flyway DDL and the table objects as
-- disagreeing — though not from running V77 itself, which sits below that test's V80 baseline and
-- is never executed there; MigrationChainTest is what actually exercises V77.
ALTER TABLE bill
    ADD COLUMN IF NOT EXISTS rebooks_bill_id BIGINT NULL
        CONSTRAINT fk_bill_rebooks_bill_id__id
        REFERENCES bill (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_bill_rebooks_bill_id ON bill (rebooks_bill_id);

ALTER TABLE zbon ADD COLUMN IF NOT EXISTS rebook_storno_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE zbon ADD COLUMN IF NOT EXISTS rebook_storno_amount DECIMAL(19, 4) NOT NULL DEFAULT 0;

-- A Storno stamps the redemption it reverses with the Storno bill's id.
ALTER TABLE voucher_redeem
    ADD COLUMN reversed_by_bill_id BIGINT NULL REFERENCES bill (id);

CREATE INDEX idx_voucher_redeem_reversed_by_bill_id
    ON voucher_redeem (reversed_by_bill_id);

-- Backfill: redemptions whose payment was already refunded by a storno are reversed
-- by that storno's reverse bill. Fixes future Z-Bons for already-storno'd voucher
-- bills whose business day is still open.
UPDATE voucher_redeem vr
SET reversed_by_bill_id = (
    SELECT p.refunded_bill_id
    FROM payment p
    WHERE p.id = vr.payment_id
      AND p.payment_status = 'REFUNDED'
      AND p.refunded_bill_id IS NOT NULL
)
WHERE EXISTS (
    SELECT 1
    FROM payment p
    WHERE p.id = vr.payment_id
      AND p.payment_status = 'REFUNDED'
      AND p.refunded_bill_id IS NOT NULL
);

-- ---------------------------------------------------------------------------
-- [source: V88__kassenbuch_line_kind.sql]
-- ---------------------------------------------------------------------------
-- Kassenbuch line kinds: classify each line (STANDARD/PFAND/GUTSCHEIN/NON_CASH_PAYMENT)
-- so the DATEV export can resolve per-line accounts (spec 2026-07-07-kassenbuch-line-kinds).
ALTER TABLE kassenbuch_line_item
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE kassenbuch_line_item
    ADD COLUMN payment_type_id BIGINT NULL
        REFERENCES payment_type(id);

-- Backfill: Z-Bon "Unbare Zahlung" lines are the only historical non-STANDARD lines.
-- Two historical belegtext formats exist: "TE:Unbare Zahlung: <type>" (since 2026-03) and
-- "Unbare Zahlung: <type>[ ...]" (earlier). Their payment type is not reliably recoverable
-- from the text -> payment_type_id stays NULL; such lines resolve whole-line to legacy
-- accounts at export, i.e. exactly today's output.
UPDATE kassenbuch_line_item
    SET kind = 'NON_CASH_PAYMENT'
    WHERE belegtext LIKE 'TE:Unbare Zahlung%'
       OR belegtext LIKE 'Unbare Zahlung%';

-- ---------------------------------------------------------------------------
-- [source: V89__kassenbuch_zbon_aggregate_flag.sql]
-- ---------------------------------------------------------------------------
-- Marks the Z-Bon's own aggregate CASH_SALE entry (bookkeeping summary of the day's
-- sales). DSFinV-K excludes marked entries from Kassenbuch-derived rows — their content
-- is already exported from the bills (spec 2026-07-09-zbon-pfand-dsfinvk).
ALTER TABLE kassenbuch_header
    ADD COLUMN is_zbon_aggregate BOOLEAN NOT NULL DEFAULT FALSE;

-- Best-effort historical backfill over the known aggregate belegtext formats, each correlated
-- on the header's OWN linked zBon so a manual entry can't match by accident:
--   1. current  "ZBon Nr. <zbon_nummer>"            (since ~2026-03)
--   2. middle   "ZBon Nr. <zbon_id>"                (the ZBon entity PK)
--   3. oldest   "Einnahme ZBon Nr. <zbon_id>"
-- This is best-effort — a hand-typed manual entry could in principle reproduce one of these
-- strings for its own linked zBon. Going forward the flag is authoritative, and the correction
-- guard (Task 2) additionally falls back to the NON_CASH_PAYMENT heuristic, so a missed
-- historical aggregate that carries non-cash lines is still protected.
UPDATE kassenbuch_header
    SET is_zbon_aggregate = TRUE
    WHERE entry_type = 'CASH_SALE'
      AND zbon_id IS NOT NULL
      AND belegtext IN (
          'ZBon Nr. ' || CAST((SELECT z.zbon_nummer FROM zbon z WHERE z.zbon_id = kassenbuch_header.zbon_id) AS VARCHAR),
          'ZBon Nr. ' || CAST(zbon_id AS VARCHAR),
          'Einnahme ZBon Nr. ' || CAST(zbon_id AS VARCHAR)
      );

-- ---------------------------------------------------------------------------
-- [source: V90__product_pfand_type.sql]
-- ---------------------------------------------------------------------------
-- Pfand (deposit) catalog + product link (spec 2026-07-09-pfand-capture-ux).
-- A pfand_type owns two system-managed backing products (a deposit charge + a return);
-- drinks link to a type via product.pfand_type_id so the client auto-attaches the deposit line.

CREATE TABLE pfand_type (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(60) NOT NULL,
    deposit_value DECIMAL(12,4) NOT NULL,
    vat_class     VARCHAR(2) NOT NULL DEFAULT 'A',
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE product
    ADD COLUMN pfand_type_id BIGINT REFERENCES pfand_type(id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- [source: V91__tse_start_timestamp.sql]
-- ---------------------------------------------------------------------------
-- Task 17b: persist the TSE hardware's own StartTransaction log time.
--
-- transactions_tse.csv's TSE_TA_START currently emits the SERVER clock
-- (tse_transaction.start_time, formatted via formatTseUnixSeconds) instead of the TSE device's
-- own StartTransaction log time. The TSE returns that log time in
-- TransactionStartResultDto.timestamp (RealTseTransactionServiceImpl.startTransaction()), but it
-- was previously discarded before reaching the DB. This mirrors the existing tse_timestamp
-- column (the TSE's FinishTransaction log time, fixed for TSE_TA_ENDE in Task 17).

ALTER TABLE tse_transaction ADD COLUMN tse_start_timestamp BIGINT NULL;

-- ---------------------------------------------------------------------------
-- [source: V92__tse_queue_open_transaction.sql]
-- ---------------------------------------------------------------------------
-- Audit TSER-12: a quick-sale bill whose FinishTransaction failed left its already-started
-- TSE transaction open FOREVER (the queue worker later quick-signed a brand-new transaction).
-- Queue entries now carry the open transaction number so the worker finishes THAT
-- transaction — no orphan accumulates on the TSE, and the Beleg is signed under the
-- transaction that actually covered the Vorgang timespan.
ALTER TABLE tse_signing_queue ADD COLUMN open_tse_transaction_number BIGINT NULL;

-- ---------------------------------------------------------------------------
-- [source: V93__shopinfo_ust_id_nr.sql]
-- ---------------------------------------------------------------------------
-- Audit STAM-8/KABS-9: USTID/LOC_USTID were filled with the Steuernummer — a German
-- Steuernummer is NOT a § 27a UStG USt-IdNr (DE + 9 digits); the fields are ALTERNATIVES
-- (STNR or USTID). The export now leaves USTID empty unless a real USt-IdNr is configured.
ALTER TABLE shop_info ADD COLUMN ust_id_nr VARCHAR(15) NULL;

