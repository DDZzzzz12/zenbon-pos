-- Squashed from V84 and V85 (release v1.1.0): the cash-book dimension and its Kassensturz close.
--
-- V97's two reversal/signal columns were assigned to this group by the plan, but they ALTER
-- `bank_statement_transaction` and `payment_allocation`, both of which are CREATEd by the
-- documents-and-invoicing group (now V81). They are therefore folded into that group's
-- CREATE TABLE statements instead -- the same split-by-target-table rule Task 4 applied to V96.
-- See the report in the squash plan; the equivalence harness compares end states, so the columns
-- land in the same place either way.
--
-- Numbering note: this file is the "cash and fiscal" group and now carries its final number, V82.
--
-- It was previously parked at V85 to sit AT the SchemaFlywayParityTest baseline, because that
-- baseline was the literal 85 and the live UPDATE below joins on `kassenbuch_header.shop_id`,
-- which the test's stub table did not have. Neither half of that constraint binds any more: the
-- parity baseline is now DERIVED (the last `__release_` marker below the lowest `__schema_`
-- group, i.e. V80), so every group in the window executes against the stubs, and the
-- `kassenbuch_header` stub was widened to `(id BIGSERIAL PRIMARY KEY, shop_id BIGINT)` so the
-- backfill runs there for real. CashBookMigrationRealDataCheck likewise no longer pins version
-- literals: it derives its slice by finding the script that contains `CREATE TABLE cash_book`.
--
-- V85, not V84, deliberately, for two reasons that both bite:
--   * SchemaFlywayParityTest baselines Flyway at V85 and requires every migration ABOVE that to run
--     against its minimal hand-seeded stubs. Its `kassenbuch_header` stub is `(id BIGSERIAL)` only,
--     so the live UPDATE below (which joins on h.shop_id) would abort that test if this file were
--     numbered above the baseline. At V85 it is baselined away, exactly as V84/V85 were before.
--   * CashBookMigrationRealDataCheck drives Flyway with baselineVersion 83 and target 85. With this
--     file at V85 its four Dataset-A assertions (A2/A3/A3b/A4) still execute over the real backup's
--     328 kassenbuch_header rows and still pass; only its "V84 and V85 both executed" bookkeeping
--     assertion fails, because there is now one file where there were two. Do not relax that
--     assertion -- the check needs adapting to the squashed numbering, which is a separate task.
--
-- Order below is LOAD-BEARING and must not be rearranged:
--   CREATE TABLE cash_book -> INSERT one Ladenkasse per shop -> ALTER kassenbuch_header ADD
--   cash_book_id -> UPDATE the backfill -> SET NOT NULL.
--
-- TIMESTAMP (not TIMESTAMPTZ) deliberately: Exposed's `datetime` column is a LocalDateTime.

-- =============================================================================================
-- Cash-book dimension: one fiscal Ladenkasse per shop + N non-fiscal books  [V84]
--
-- Backfills every existing kassenbuch_header into its shop's fiscal book, so nothing is
-- reclassified and behaviour is unchanged for shops that never create a second book.
-- =============================================================================================
CREATE TABLE cash_book (
    id              BIGSERIAL PRIMARY KEY,
    -- VERIFIED: ShopInfoTable is LongIdTable("shop_info", "shop_id") — the PK column is
    -- shop_id and it is BIGINT, not a UUID named id.
    shop_id         BIGINT       NOT NULL REFERENCES shop_info (shop_id),
    name            VARCHAR(60)  NOT NULL,
    is_fiscal       BOOLEAN      NOT NULL,
    datev_account   VARCHAR(5)   NOT NULL,
    kost1           VARCHAR(36),
    kost2           VARCHAR(36),
    close_interval  VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_cash_book_shop ON cash_book (shop_id);

-- Exactly one fiscal book per shop. Partial index — Postgres only; H2 test schemas come from
-- SchemaManager, not Flyway, so the service-level check is what holds in tests.
CREATE UNIQUE INDEX idx_cash_book_one_fiscal_per_shop
    ON cash_book (shop_id) WHERE is_fiscal;

-- LIVE data statement. Seeds one fiscal cash book per existing shop. `shop_info` predates the
-- squash window and has rows on every install, so this INSERT is the only thing that creates the
-- cash book an upgraded install then references. DO NOT REMOVE.
-- One Ladenkasse per existing shop.
INSERT INTO cash_book (shop_id, name, is_fiscal, datev_account, close_interval, created_at)
SELECT shop_id, 'Ladenkasse', TRUE, '1000', 'MANUAL', NOW()
FROM shop_info;

ALTER TABLE kassenbuch_header ADD COLUMN cash_book_id BIGINT REFERENCES cash_book (id);
ALTER TABLE kassenbuch_header ADD COLUMN counterparty_book_id BIGINT REFERENCES cash_book (id);
ALTER TABLE kassenbuch_header ADD COLUMN transfer_id VARCHAR(36);

-- LIVE backfill. `kassenbuch_header` predates the squash window and is full of real fiscal rows on
-- every install (328 in the 2026-08 production backup); without this every existing header keeps a
-- NULL cash_book_id and the SET NOT NULL below aborts the upgrade. DO NOT REMOVE.
UPDATE kassenbuch_header h
SET cash_book_id = cb.id
FROM cash_book cb
WHERE cb.shop_id = h.shop_id AND cb.is_fiscal;

ALTER TABLE kassenbuch_header ALTER COLUMN cash_book_id SET NOT NULL;

CREATE INDEX idx_kassenbuch_header_cash_book ON kassenbuch_header (cash_book_id);
CREATE INDEX idx_kassenbuch_header_transfer ON kassenbuch_header (transfer_id);

-- =============================================================================================
-- Kassensturz on a non-fiscal cash book  [V85]
--
-- Counted vs book balance, difference booked, period locked. The fiscal Ladenkasse is never
-- closed here — the Z-Bon is its Kassenabschluss.
-- =============================================================================================
CREATE TABLE cash_book_close (
    id                    BIGSERIAL     PRIMARY KEY,
    cash_book_id          BIGINT        NOT NULL REFERENCES cash_book (id),
    period_start          DATE          NOT NULL,
    period_end            DATE          NOT NULL,
    book_balance          DECIMAL(19,4) NOT NULL,
    counted_balance       DECIMAL(19,4) NOT NULL,
    difference            DECIMAL(19,4) NOT NULL,
    difference_header_id  BIGINT        REFERENCES kassenbuch_header (id),
    -- VERIFIED: EmployeeTable is UUIDTable("employees", "employee_id") — the table is `employees`
    -- (PLURAL) and its PK column is `employee_id`, of type UUID.
    closed_by             UUID          NOT NULL REFERENCES employees (employee_id),
    closed_at             TIMESTAMP     NOT NULL,
    notes                 VARCHAR(500)
);

CREATE INDEX idx_cash_book_close_book ON cash_book_close (cash_book_id, period_end DESC);


-- =============================================================================================
-- Folded in before the v1.1.0 release: V118..V123, the audit trace id and the TSE signing-queue
-- work (late-signing record, Beleg-age override column, transaction state, hex serial,
-- explanation register). They had landed ABOVE the release marker V90; folding them here keeps the
-- marker the last migration. Order is preserved: the explanation backfill reads the state and hex
-- columns added just before it. Every statement below targets a table that predates this window
-- (transaction_audit_log, tse_signing_queue, kassenbuch_header, tse_transaction) or creates a new
-- one, so each UPDATE and INSERT ... SELECT is live on an upgrade and was kept verbatim. Wherever a
-- comment below says "this file" or names V118..V123, it means the section it heads.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- formerly V118__add_trace_id_to_transaction_audit_log
-- ---------------------------------------------------------------------------------------------
-- Correlates an audit row with the server log line and with the trace id the operator is shown.
--
-- A fresh install builds this table from Exposed and baselines Flyway, so it never runs this file;
-- an existing install has no other way to acquire the column, because SchemaManager.addMissingColumns()
-- has no production caller. Additive and nullable: no existing row changes and no fiscal column moves.
ALTER TABLE transaction_audit_log
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_transaction_audit_log_trace_id
    ON transaction_audit_log (trace_id);

-- ---------------------------------------------------------------------------------------------
-- formerly V119__tse_late_signing
-- ---------------------------------------------------------------------------------------------
-- One row per Vorgang that could not be TSE-signed immediately and went to the signing queue.
-- It documents the outage for the Vorgang's whole lifetime (AEAO zu § 146a): why it was queued,
-- since when, and when -- if ever -- it was signed late or its Nachsignierung abandoned.
--
-- Owned by the signing queue, but NEVER deleted by queue cleanup: tse_signing_queue rows are
-- purged by cleanupOldEntries, and without this table a Vorgang signed late becomes
-- indistinguishable from one signed on time once its status flips to SIGNED.
--
-- The foreign key is named explicitly and spells out ON DELETE and ON UPDATE, because that is what
-- Exposed's `reference(...)` generates and what SchemaFlywayParityTest compares against.
CREATE TABLE tse_late_signing (
    id                  BIGSERIAL PRIMARY KEY,
    entity_type         VARCHAR(20)  NOT NULL,
    reference_id        VARCHAR(100) NOT NULL,
    queued_at           TIMESTAMP    NOT NULL,
    reason_code         VARCHAR(50)  NOT NULL,
    reason_text         VARCHAR(500) NOT NULL,
    signed_at           TIMESTAMP,
    tse_transaction_id  BIGINT       CONSTRAINT fk_tse_late_signing_tse_transaction_id__id
                                         REFERENCES tse_transaction (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    abandoned_at        TIMESTAMP,
    CONSTRAINT uq_tse_late_signing_ref UNIQUE (entity_type, reference_id)
);

-- Backfill from the queue rows that still exist. Their original reason was never stored; the last
-- retry error is the best evidence left, so the code says UNKNOWN_HISTORIC rather than inventing a
-- cause. Rows already purged by queue cleanup cannot be recovered.
INSERT INTO tse_late_signing (entity_type, reference_id, queued_at, reason_code, reason_text, signed_at, tse_transaction_id, abandoned_at)
SELECT q.entity_type,
       q.reference_id,
       q.created_at,
       'UNKNOWN_HISTORIC',
       COALESCE(LEFT(q.last_error, 500), 'Grund nicht erfasst (vor Version 1.1.0 in die Warteschlange gestellt)'),
       q.signed_at,
       q.tse_transaction_id,
       CASE WHEN q.signing_status = 'CANCELLED' THEN COALESCE(q.last_attempt_at, q.created_at) END
  FROM tse_signing_queue q
ON CONFLICT (entity_type, reference_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- formerly V120__kassenbuch_backdate_override (column only)
-- ---------------------------------------------------------------------------------------------
-- Kassenbuch backdating override: a Beleg date older than the shop's limit
-- (kassenbuch_max_beleg_age_days, default 31) may be booked with a mandatory reason, by a holder of
-- CASHBOOK_BACKDATE or through a manager's approval.
--
-- A fresh install builds kassenbuch_header from Exposed, seeds its permission groups from
-- CapabilityBundles and baselines Flyway, so it never runs this file. An existing install has no other
-- way to acquire the column; the grant is also reconciled at boot by SchemaManager, but the migrated
-- database must be correct on its own (SeededGrantsMatchBundlesTest runs the whole chain).
-- Additive and nullable: no existing row changes and no fiscal column moves.

ALTER TABLE kassenbuch_header
    ADD COLUMN IF NOT EXISTS late_booking_reason VARCHAR(500);
--
-- The CASHBOOK_BACKDATE grant this migration also carried now lives in V85, the single
-- final-state seed for every capability grant in the window, with its check folded in.

-- ---------------------------------------------------------------------------------------------
-- formerly V121__tse_transaction_state
-- ---------------------------------------------------------------------------------------------
-- E2E R2-7 (design 2026-09-22 §4.1): a Vorgang's TSE transaction is now recorded when it STARTS,
-- before the device signs anything. A failed write can then no longer throw a signature away; the
-- retry finishes the same TANR. A TANR the device already closed is SUPERSEDED, replaced by a new
-- one, and exported as an extra, unsigned TSE_Transaktionen row on the same Bon (TR-03153-1 §9.4).
--
-- Existing rows: signed -> FINISHED, unsigned -> STARTED. An unsigned row today is a start whose
-- finish never landed (the quick-sale era's open transactions).
ALTER TABLE tse_transaction ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'FINISHED';
UPDATE tse_transaction SET state = 'STARTED' WHERE signature IS NULL;

ALTER TABLE tse_transaction ADD COLUMN superseded_by_id BIGINT;

-- One LIVE transaction per Vorgang — the database-level backstop against signing a Vorgang twice.
--
-- Nothing locks a Vorgang while it is being signed, so the retry sweeper and a manual retry (or the
-- online path racing the queue) can both find no current row, both start a transaction on the TSE
-- and both finish it: two signatures over the same payload in write-once memory, which is exactly
-- what the state column exists to prevent. With a UNIQUE index the loser's FIRST commit — the
-- STARTED row — fails instead; `TseVorgangSigner.persisting` turns that into TSE_SIGNING_FAILED,
-- the Vorgang is queued, and the retry adopts the winner's row. The loser's empty open transaction
-- on the device is closed by the maintenance task, as any other aborted start is.
--
-- SUPERSEDED rows are excluded because a Vorgang may legitimately accumulate them: every replaced
-- TANR stays on file and is exported as an extra, unsigned transactions_tse.csv row. Rows with no
-- reference (pre-M7 history, and simulation rows written before the link existed) are excluded
-- because NULLs carry no identity to compare.
--
-- TWO BRANCHES, and the second one is the point: an install that ALREADY holds two live rows for
-- one (reference_id, entity_type) is precisely an install hurt by the double-signing this work
-- fixes, and a bare CREATE UNIQUE INDEX would abort its upgrade — with no later migration able to
-- repair it, because the chain stops at the failure. So:
--   * no duplicates  -> the index is created UNIQUE and is the backstop described above;
--   * duplicates     -> the same index is created PLAIN and a NOTICE names what was found. The
--                       upgrade proceeds; the duplicates are then found and explained by the TSE
--                       findings work, which is where deciding WHICH signature is the Vorgang's
--                       belongs — it is a judgement about WORM contents, not something a migration
--                       may guess.
-- A site that lands in the second branch keeps only the application-level protection until the
-- duplicates are resolved and the index is rebuilt UNIQUE by hand.
--
-- To check a production backup BEFORE upgrading:
--   SELECT reference_id, entity_type, COUNT(*)
--     FROM tse_transaction
--    WHERE state <> 'SUPERSEDED' AND reference_id IS NOT NULL AND entity_type IS NOT NULL
--    GROUP BY reference_id, entity_type
--   HAVING COUNT(*) > 1;
-- (On a database that has not yet run this migration the `state` column does not exist; read
--  `signature IS NOT NULL` there instead, which is what V121 turns into FINISHED above.)
--
-- NOT declared on `TseTransactionTable`: H2 has no partial indexes and the H2 test suite builds its
-- schema from Exposed, so declaring it there would break every H2 test. The asymmetry is noted in
-- the table object. `V121TseOneLiveRowPerVorgangTest` (@Tag("postgres")) covers BOTH branches.
DO $$
DECLARE
    duplicate_pairs  INTEGER;
    duplicate_sample TEXT;
BEGIN
    -- The COUNT is over the UNLIMITED grouping: an operator told "20" when 500 Vorgänge are
    -- affected would badly misjudge the work ahead. Only the SAMPLE is capped.
    SELECT COUNT(*)
      INTO duplicate_pairs
      FROM (
             SELECT reference_id, entity_type
               FROM tse_transaction
              WHERE state <> 'SUPERSEDED'
                AND reference_id IS NOT NULL
                AND entity_type IS NOT NULL
              GROUP BY reference_id, entity_type
             HAVING COUNT(*) > 1
           ) all_duplicates;

    SELECT string_agg(d.reference_id || '/' || d.entity_type || ' x' || d.live_rows, ', ')
      INTO duplicate_sample
      FROM (
             SELECT reference_id, entity_type, COUNT(*) AS live_rows
               FROM tse_transaction
              WHERE state <> 'SUPERSEDED'
                AND reference_id IS NOT NULL
                AND entity_type IS NOT NULL
              GROUP BY reference_id, entity_type
             HAVING COUNT(*) > 1
              LIMIT 20
           ) d;

    IF duplicate_pairs = 0 THEN
        CREATE UNIQUE INDEX tse_transaction_one_live_per_vorgang
            ON tse_transaction (reference_id, entity_type)
            WHERE state <> 'SUPERSEDED' AND reference_id IS NOT NULL AND entity_type IS NOT NULL;
    ELSE
        CREATE INDEX tse_transaction_one_live_per_vorgang
            ON tse_transaction (reference_id, entity_type)
            WHERE state <> 'SUPERSEDED' AND reference_id IS NOT NULL AND entity_type IS NOT NULL;
        RAISE NOTICE 'tse_transaction_one_live_per_vorgang created NON-UNIQUE: % Vorgang(s) already carry more than one live TSE transaction. First 20: %. Each of these was signed more than once and must be explained before the index can be made unique.',
                     duplicate_pairs, COALESCE(duplicate_sample, '');
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- formerly V122__tse_serial_hex
-- ---------------------------------------------------------------------------------------------
-- E2E R2-5 (design 2026-09-22 D6): BSI TR-03153-1 §9.3.2 requires the TSE serial as a string to be the
-- hex string, and forbids Base64 "im Rahmen der Identifizierung und Zuordnung der TSE". Before H7
-- (2026-07-14), and on the signing-queue path until the 2026-09 signer, rows stored the serial Base64
-- as the device delivered it, so one device appeared under two serials.
--
-- tse_serial_hex is the canonical form every reader uses. tse_serial_number is NOT rewritten
-- (owner's decision): historic rows keep the value they were written with.
ALTER TABLE tse_transaction ADD COLUMN tse_serial_hex VARCHAR(100);

UPDATE tse_transaction
   SET tse_serial_hex = CASE
        -- exactly a 32-byte serial in Base64; the simulator's short values never match
        WHEN tse_serial_number ~ '^[A-Za-z0-9+/]{43}=$' THEN upper(encode(decode(tse_serial_number, 'base64'), 'hex'))
        WHEN tse_serial_number ~ '^[0-9A-Fa-f]{64}$' THEN upper(tse_serial_number)
        -- anything else (SIM-TSE-001 and its encodings) verbatim, like the export's tseSerialKey
        ELSE tse_serial_number
       END
 WHERE tse_serial_number IS NOT NULL;

-- Unique when history allows. The same (serial, TANR) in two encodings is a real duplicate that the
-- old raw-string key could not catch; an upgrade must not fail on it, so the index is then plain
-- and the TSE findings check reports the duplicate for explanation.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM tse_transaction
         WHERE tse_serial_hex IS NOT NULL AND transaction_number IS NOT NULL
         GROUP BY tse_serial_hex, transaction_number
        HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'tse_transaction holds the same (serial, TANR) in two encodings; idx_tse_tx_serial_hex_tanr created non-unique';
        CREATE INDEX idx_tse_tx_serial_hex_tanr ON tse_transaction (tse_serial_hex, transaction_number);
    ELSE
        CREATE UNIQUE INDEX idx_tse_tx_serial_hex_tanr ON tse_transaction (tse_serial_hex, transaction_number);
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- formerly V123__tse_explanation
-- ---------------------------------------------------------------------------------------------
-- Design 2026-09-22 D7: append-only explanations for TANRs without an ordinary POS record. The
-- month-end TANR continuity check and the TAR reconciliation find them; the system explains the
-- ones whose cause is certain, an operator the rest. Never updated or deleted: a correction is a
-- new row naming the old one.
CREATE TABLE tse_explanation (
    id                      BIGSERIAL PRIMARY KEY,
    tse_serial_hex          VARCHAR(100)  NOT NULL,
    tanr_from               BIGINT        NOT NULL,
    tanr_to                 BIGINT        NOT NULL,
    category                VARCHAR(40)   NOT NULL,
    text                    VARCHAR(2000) NOT NULL,
    source                  VARCHAR(10)   NOT NULL,
    created_at              TIMESTAMP     NOT NULL,
    created_by_employee_id  VARCHAR(100),
    supersedes_id           BIGINT
);
CREATE INDEX idx_tse_explanation_serial_range ON tse_explanation (tse_serial_hex, tanr_from, tanr_to);

-- An explanation may be corrected ONCE. The correction is then itself corrected, which is what
-- makes a chain A -> B -> C readable: exactly one entry is current per range, and every earlier
-- link has exactly one successor. Two corrections naming the same entry would instead leave BOTH
-- current for that range, and which one covers the finding would come down to row order.
--
-- TseFindingService.requireCorrectable rejects that with a sentence the operator can act on and is
-- the guard that normally fires; it reads and then writes, though, so two concurrent corrections
-- can both pass it. THIS is the structural one that cannot be raced. A fresh table cannot violate
-- it, and the backfill above writes only NULL supersedes_id, so no tolerant fallback is needed.
CREATE UNIQUE INDEX tse_explanation_one_correction_per_entry
    ON tse_explanation (supersedes_id) WHERE supersedes_id IS NOT NULL;

-- TANRs superseded between V121 and this migration get their system explanation now.
INSERT INTO tse_explanation (tse_serial_hex, tanr_from, tanr_to, category, text, source, created_at)
SELECT t.tse_serial_hex, t.transaction_number, t.transaction_number, 'SUPERSEDED',
       'TSE-Transaktion ' || t.transaction_number || ' abgeschlossen, Antwort nicht erhalten; der Vorgang wurde unter neuer TANR signiert (BSI TR-03153-1 §9.4).',
       'SYSTEM', now()
  FROM tse_transaction t
 WHERE t.state = 'SUPERSEDED' AND t.tse_serial_hex IS NOT NULL AND t.transaction_number IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Employee-tip payouts (2026-10, F51) -- folded here, not a new file, because V90 (the v1.1.0
-- release marker) must stay the last migration until the tag.
-- ---------------------------------------------------------------------------------------------
-- One row per payout of a closing's employee tips (TrinkgeldAN) to an employee, AFTER the closing.
-- The cash movement itself is an ordinary signed Kassenbuch withdrawal (line kind EMPLOYEE_TIP);
-- this row only links it to (closing, employee) so the still-payable remainder is computable.
-- A reversal (Storno) of a payout is a second row with reverses_payout_id set.
-- Append-only. No data to backfill: payouts did not exist before this table.
-- F59: method BANK_PAYROLL = paid by bank transfer / payroll. No cash moved, so such a row (and its
-- reversal) has NO Kassenbuch entry: kassenbuch_header_id is NULL exactly then (method_entry CHECK).
CREATE TABLE employee_tip_payout (
    id                      BIGSERIAL PRIMARY KEY,
    zbon_id                 BIGINT         NOT NULL,
    employee_id             UUID           NOT NULL,
    kassenbuch_header_id    BIGINT,
    method                  VARCHAR(16)    NOT NULL DEFAULT 'CASH',
    amount                  DECIMAL(19, 4) NOT NULL,
    created_by_employee_id  UUID           NOT NULL,
    created_at              TIMESTAMP      NOT NULL,
    -- Set on a reversal row: the payout it reverses (append-only correction; unique = once).
    reverses_payout_id      BIGINT,
    CONSTRAINT employee_tip_payout_amount_positive CHECK (amount > 0),
    CONSTRAINT employee_tip_payout_method_valid CHECK (method IN ('CASH', 'BANK_PAYROLL')),
    CONSTRAINT employee_tip_payout_method_entry CHECK ((method = 'CASH') = (kassenbuch_header_id IS NOT NULL)),
    -- Constraint names and RESTRICT match what Exposed generates (SchemaFlywayParityTest).
    CONSTRAINT fk_employee_tip_payout_zbon_id__zbon_id FOREIGN KEY (zbon_id)
        REFERENCES zbon (zbon_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_employee_tip_payout_employee_id__employee_id FOREIGN KEY (employee_id)
        REFERENCES employees (employee_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_employee_tip_payout_kassenbuch_header_id__id FOREIGN KEY (kassenbuch_header_id)
        REFERENCES kassenbuch_header (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_employee_tip_payout_created_by_employee_id__employee_id FOREIGN KEY (created_by_employee_id)
        REFERENCES employees (employee_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_employee_tip_payout_reverses_payout_id__id FOREIGN KEY (reverses_payout_id)
        REFERENCES employee_tip_payout (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE UNIQUE INDEX employee_tip_payout_kassenbuch_header_id_unique ON employee_tip_payout (kassenbuch_header_id);
CREATE UNIQUE INDEX employee_tip_payout_reverses_payout_id_unique ON employee_tip_payout (reverses_payout_id);
CREATE INDEX employee_tip_payout_zbon_id_employee_id ON employee_tip_payout (zbon_id, employee_id);

-- =============================================================================================
-- bill_number_sequence — the Kassenbeleg counter, one row per month (period = 'yyMM').
--
-- bill_number (RE-yyMMdd-N) took N from a count(*) of the month's bills, which two parallel
-- transactions read alike: real-TSE QA rt12 got a duplicate RE-260506-39 and a 500. A row locked
-- FOR UPDATE serialises the issuances across transactions and processes, and its increment rolls
-- back with a failed bill, so the month's sequence stays unique and gapless (cf. ar_number_sequence).
--
-- No backfill: the application creates a month's row on its first bill, seeded one past the
-- highest N already issued in that month, so an upgrade mid-month continues the sequence.
-- bill.bill_number is already unique (bill_bill_number_unique).
-- =============================================================================================
CREATE TABLE bill_number_sequence (
    id         BIGSERIAL PRIMARY KEY,
    period     VARCHAR(4) NOT NULL,
    next_value BIGINT     NOT NULL
);

CREATE UNIQUE INDEX bill_number_sequence_period_unique ON bill_number_sequence (period);

-- =============================================================================================
-- order_number_sequence — the order-number counter (t_order.order_number = ORD-yyyyMMdd-N).
-- Folded here, not a new file, because V90 (the v1.1.0 release marker) must stay the last
-- migration until the tag.
--
-- N took max(t_order.id) + 1, read in the creating transaction: two cashiers creating an order
-- at the same moment drew the same N and one of them died on t_order_order_number_unique with a
-- 500 (POST /api/orders, seen under a 3-till stress loop). A row locked FOR UPDATE serialises
-- the issuances across transactions and processes (cf. bill_number_sequence, ar_number_sequence).
--
-- No backfill: the application creates the row on the first order, seeded one past the highest
-- N already issued, so an upgrade continues the numbering instead of restarting it.
-- =============================================================================================
CREATE TABLE order_number_sequence (
    id         BIGSERIAL PRIMARY KEY,
    prefix     VARCHAR(10) NOT NULL,
    next_value BIGINT      NOT NULL
);

CREATE UNIQUE INDEX order_number_sequence_prefix_unique ON order_number_sequence (prefix);

-- =============================================================================================
-- tse_export_archive — the TSE archive register (real-TSE QA rt14). Folded here, not a new file,
-- because V90 (the v1.1.0 release marker) must stay the last migration until the tag.
--
-- After an export-and-delete the archive it wrote is the only copy of the erased TSE entries, and
-- the DSFinV-K export refused that period claiming the device had been swapped. Every such archive
-- is recorded here (and older ones can be registered by an administrator); the DSFinV-K export ships
-- a registered archive for a period that reaches back into the erased entries, after re-checking its
-- SHA-256. Ranges are what the file holds (NULL when it holds no message of that kind); log times are
-- Unix seconds on the TSE's clock. No backfill: an archive written before this table existed is
-- registered by hand (POST /api/tse/archives).
-- =============================================================================================
CREATE TABLE tse_export_archive (
    id                      BIGSERIAL     PRIMARY KEY,
    file_name               VARCHAR(255)  NOT NULL,
    file_path               VARCHAR(1024) NOT NULL,
    tse_serial_hex          VARCHAR(100)  NOT NULL,
    tanr_from               BIGINT,
    tanr_to                 BIGINT,
    signature_counter_from  BIGINT,
    signature_counter_to    BIGINT,
    log_time_from           BIGINT,
    log_time_to             BIGINT,
    size_bytes              BIGINT        NOT NULL,
    sha256                  VARCHAR(64)   NOT NULL,
    origin                  VARCHAR(20)   NOT NULL,
    created_at              TIMESTAMP     NOT NULL,
    created_by_employee_id  VARCHAR(100)
);

CREATE UNIQUE INDEX tse_export_archive_sha256_unique ON tse_export_archive (sha256);
CREATE INDEX idx_tse_export_archive_serial_tanr ON tse_export_archive (tse_serial_hex, tanr_from, tanr_to);
