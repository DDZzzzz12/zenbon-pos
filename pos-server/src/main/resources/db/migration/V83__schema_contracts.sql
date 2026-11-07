-- Squashed from V95, V96 (contract half) and V117 (release v1.1.0): recurring obligations and the
-- instalments they fall due in.
--
-- V96's `ALTER TABLE contract ADD COLUMN sepa_mandate_id` was carved out of the
-- documents-and-invoicing group by the same split-by-target-table rule that group's header
-- describes -- `contract` is created HERE, in a file that runs after it. The carve-out file
-- V96__contract_sepa_mandate_id.sql was deleted and its column folded into the CREATE TABLE
-- below, marked [V96]. V117's due_weekday column and its CHECK are folded the same way, marked
-- [V117]; both target `contract`, which is created in this file, so neither can stay an ALTER.
--
-- No backfills existed in any of the three source files -- none of them carried a single data
-- statement -- so nothing was dropped.
--
-- Numbering note: this file is the "contracts" group and now carries its final number, V83. It
-- cannot move below V81: `contract` and `contract_instalment` reference `supplier`, `invoice` and
-- `payment_allocation`, all created by V81__schema_documents_and_invoicing.sql. The equivalence
-- harness compares end states, not version numbers.
--
-- Mirrors the Exposed objects in core-data/.../features/contract/database/tables/ and the added
-- contract_id column on InvoiceTable -- SchemaFlywayParityTest asserts the two agree, so a change
-- here must be made to the Exposed object as well.
--
-- TIMESTAMP (not TIMESTAMPTZ) deliberately: Exposed's `datetime` is a LocalDateTime.
--
-- No COMMENT ON COLUMN anywhere below: `invoice` and `payment_allocation` are both in the parity
-- test's table set, and a stored column comment makes Exposed emit a pending
-- `COMMENT ON COLUMN … IS NULL`, failing parity. `--` comments are where the why belongs.

-- ---------------------------------------------------------------------------------------------
-- contract
--
-- expects_invoice separates the two kinds this model must both support. FALSE is a standing order
-- that never produces a document: contract -> instalment -> bank transaction, and waiting for an
-- invoice would flag every month as a problem. TRUE means an invoice arrives each period and the
-- direct debit settles *that invoice*; the contract's job is to expect it.
--
-- tolerance_pct / tolerance_abs are both nullable because a variable obligation (Strom, Telefon)
-- legitimately differs month to month while a fixed rent wants neither band — and a band of zero is
-- not the same statement as "no band expected".
--
-- due_day is 1-31. A value past the end of a short month clamps to the last day rather than
-- skipping the month: the rent is still owed in February.
--
-- [V96] sepa_mandate_id: a mandate is granted for one agreement, so two contracts with the same
-- supplier carry different mandates. That is what lets the matcher's signal 3 reach a *contract
-- instalment* rather than stopping at the supplier. VARCHAR(35): a SEPA mandate reference (MndtId)
-- is 35 characters. Unindexed on purpose — contract is a tens-of-rows table and an unused index
-- costs on every write.
--
-- [V117] due_weekday: the weekday a WEEKLY contract falls due on. ISO-8601 numbering, 1 = Monday
-- .. 7 = Sunday, matching kotlinx.datetime's DayOfWeek.isoDayNumber so no translation table is
-- needed anywhere. Nullable, and null for every existing row: the four intervals that exist today
-- are all month-stepped and anchor on `due_day`, which stays exactly as it is. Only WEEKLY reads
-- this column, and no WEEKLY contract can exist yet. Not NOT NULL with a default: a default would
-- claim every historical contract falls due on a Monday, which is a statement about data nobody
-- made. The "required for WEEKLY" rule is enforced in the service, where it can be a 400 naming the
-- field, and in the schedule, which rejects a null outright.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE contract (
    id                  BIGSERIAL PRIMARY KEY,
    shop_id             BIGINT         NOT NULL CONSTRAINT fk_contract_shop_id__shop_id
                                       REFERENCES shop_info (shop_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    supplier_id         BIGINT         CONSTRAINT fk_contract_supplier_id__id
                                       REFERENCES supplier (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    name                VARCHAR(255)   NOT NULL,
    description         TEXT,
    expected_amount     DECIMAL(19, 4),
    tolerance_pct       DECIMAL(9, 4),
    tolerance_abs       DECIMAL(19, 4),
    "interval"          VARCHAR(20)    NOT NULL,
    due_day             INT            NOT NULL,
    due_weekday         INT,                       -- [V117]
    start_date          TIMESTAMP      NOT NULL,
    end_date            TIMESTAMP,
    notice_period_days  INT,
    expects_invoice     BOOLEAN        NOT NULL DEFAULT FALSE,
    default_account_no  VARCHAR(20),
    default_bu_code     VARCHAR(10),
    default_cost_center VARCHAR(20),
    sepa_mandate_id     VARCHAR(35),               -- [V96]
    document_id         BIGINT         CONSTRAINT fk_contract_document_id__id
                                       REFERENCES documents (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    is_active           BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP      NOT NULL,
    updated_at          TIMESTAMP,
    CONSTRAINT chk_contract_due_weekday CHECK (due_weekday IS NULL OR due_weekday BETWEEN 1 AND 7)  -- [V117]
);

CREATE INDEX idx_contract_shop_id     ON contract (shop_id);
CREATE INDEX idx_contract_supplier_id ON contract (supplier_id);
CREATE INDEX idx_contract_is_active   ON contract (is_active);

COMMENT ON TABLE contract IS
    'A recurring obligation - rent, Strom, Telefon, leasing. expects_invoice separates the invoice-less standing order from the kind where a document arrives each period and the direct debit settles that invoice.';

-- ---------------------------------------------------------------------------------------------
-- contract_instalment
--
-- One expected payment, materialised ahead of its due date. The UNIQUE (contract_id, due_date) is
-- what makes the generator idempotent: the job may run twice after a restart, and a duplicated
-- instalment would double the expected outgoings. A "have I run today" flag would not survive one.
--
-- invoice_id stays NULL forever on an invoice-less contract, and is filled on the other kind once
-- the period's invoice is identified.
--
-- status never holds OVERDUE. That is derived at read time from due_date and open_amount: a stored
-- OVERDUE would be wrong from the moment the date passes until something runs, which is worse than
-- having no status at all. The generator writes only OPEN.
--
-- open_amount lands here so the schema settles in one migration; nothing maintains it until slice
-- 4b unlocks CONTRACT_INSTALMENT as an allocation target.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE contract_instalment (
    id              BIGSERIAL PRIMARY KEY,
    contract_id     BIGINT         NOT NULL CONSTRAINT fk_contract_instalment_contract_id__id
                                   REFERENCES contract (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    due_date        TIMESTAMP      NOT NULL,
    expected_amount DECIMAL(19, 4),
    invoice_id      BIGINT         CONSTRAINT fk_contract_instalment_invoice_id__id
                                   REFERENCES invoice (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    status          VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    open_amount     DECIMAL(19, 4),
    created_at      TIMESTAMP      NOT NULL
);

CREATE UNIQUE INDEX contract_instalment_contract_id_due_date_unique
    ON contract_instalment (contract_id, due_date);
CREATE INDEX idx_contract_instalment_due_date   ON contract_instalment (due_date);
CREATE INDEX idx_contract_instalment_invoice_id ON contract_instalment (invoice_id);

COMMENT ON TABLE contract_instalment IS
    'One expected payment under a contract, materialised ahead of its due date. Unique per (contract_id, due_date), which is what makes generation idempotent across a restart.';

-- ---------------------------------------------------------------------------------------------
-- invoice.contract_id - the contract this invoice arrived under, when it arrived under one.
--
-- Nullable and additive: most invoices are one-off purchases with no contract behind them.
-- Stays an ALTER: `invoice` is created by V86__schema_documents_and_invoicing.sql, which runs
-- before this file.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE invoice
    ADD COLUMN contract_id BIGINT CONSTRAINT fk_invoice_contract_id__id
        REFERENCES contract (id) ON DELETE SET NULL ON UPDATE RESTRICT;

CREATE INDEX idx_invoice_contract_id ON invoice (contract_id);

-- ---------------------------------------------------------------------------------------------
-- payment_allocation.contract_instalment_id - the foreign key the documents-and-invoicing group
-- could not add.
--
-- That group creates the column as a plain nullable BIGINT because contract_instalment did not
-- exist yet. It does now, so the constraint goes on. The column and its index already exist; only
-- the FK is new. SET NULL rather than CASCADE: deleting an instalment must not silently delete the
-- record that money moved.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE payment_allocation
    ADD CONSTRAINT fk_payment_allocation_contract_instalment_id__id
        FOREIGN KEY (contract_instalment_id) REFERENCES contract_instalment (id)
        ON DELETE SET NULL ON UPDATE RESTRICT;
