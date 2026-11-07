-- Squashed from V86, V88-V94, V96, V97, V99-V105 (release v1.1.0).
--
-- V97 was assigned to the cash-and-fiscal group by the plan, but both of its ALTERs target tables
-- created in THIS file (bank_statement_transaction, payment_allocation). The cash-and-fiscal group
-- runs after this one, so keeping them there would have been an ordering error; by the same
-- split-by-target-table rule V96 was split under, V97's two columns are folded into the CREATE
-- TABLEs below and marked [V97].
-- Backfills from V93, V94 and V101 were dropped: every target table is created in this
-- file, so on the only path these migrations ever run -- an upgrade from V80 -- they
-- match zero rows. See docs/superpowers/plans/2026-08-31-migration-squash-v81-v117.md.
--
-- V96's `ALTER TABLE contract ADD COLUMN sepa_mandate_id` is NOT here: `contract` is created by
-- V95, which belongs to the contracts group that runs AFTER this file. That column is folded into
-- the contracts group's CREATE TABLE instead, in V83__schema_contracts.sql.
--
-- Numbering note: this file is the "documents and invoicing" group and now carries its final
-- number, V81 -- the bottom of the window. It must stay below the contracts group (V83), whose
-- `contract`/`contract_instalment` reference `supplier`, `invoice` and `payment_allocation`, all
-- created here. The equivalence harness compares end states, not version numbers.
--
-- TIMESTAMP (not TIMESTAMPTZ) throughout, deliberately: Exposed's `datetime` column is a
-- LocalDateTime and creates TIMESTAMP WITHOUT TIME ZONE. Using TIMESTAMPTZ would silently diverge
-- from the H2 schema SchemaFlywayParityTest compares against.
--
-- Ordering inside this file is by foreign-key dependency, not by original version number:
-- supplier and bank_account come first because invoice and bank_statement reference them.

-- =============================================================================================
-- Supplier (creditor) master data  [V90]
-- =============================================================================================
-- The creditor ROLE only. Identity -- name, legal_name, vat_id, tax_number, the address block,
-- email and phone, plus normalized_name -- is NOT declared here: it lives on `business_partner`
-- (see the business-partner group later in this chain), and declaring it here would mean creating
-- eleven columns in one migration only to drop them again a few migrations later in the same
-- upgrade run. What remains is what is genuinely the creditor's: the DATEV booking defaults, the
-- payment terms and the SEPA creditor id.
CREATE TABLE supplier (
    id                   BIGSERIAL PRIMARY KEY,
    creditor_no          VARCHAR(20),
    default_account_no   VARCHAR(20),
    default_bu_code      VARCHAR(10),
    default_cost_center  VARCHAR(20),
    payment_terms_days   INT,
    is_provisional       BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    notes                TEXT,
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP,
    -- [V96] The party collecting the money is the supplier, so the SEPA Creditor Identifier sits
    -- here. NOT supplier.creditor_no, which is the unrelated DATEV Kreditorenkonto. Unindexed:
    -- looked up by equality, but supplier is a tens-of-rows table and an unused index costs on
    -- every write.
    sepa_creditor_id     VARCHAR(35)
);

-- No index on vat_id or normalized_name: both columns belong to `business_partner`, which indexes
-- them itself. The uniqueness that used to live on `supplier_vat_id_unique` is now one creditor
-- role per company -- `idx_supplier_partner_unique`, declared with the party model.
CREATE INDEX idx_supplier_is_provisional ON supplier (is_provisional);

COMMENT ON TABLE supplier IS
    'Creditor master data. Separate from customers, which is a natural-person POS loyalty record. invoice.seller_* remains the supplier-asserted snapshot — renaming here must not rewrite an old invoice.';

-- No supplier_id: a bank account belongs to the company, not to one direction of trade, so the
-- owning column is `partner_id` and it is added with the party model later in this chain. Declaring
-- the creditor-role link here would only be dropped again in the same upgrade run.
CREATE TABLE supplier_bank_account (
    id                        BIGSERIAL PRIMARY KEY,
    iban                      VARCHAR(34) NOT NULL,
    bic                       VARCHAR(11),
    first_seen_on_invoice_id  BIGINT,
    verified                  BOOLEAN     NOT NULL DEFAULT FALSE
);

-- Only the IBAN index here. The owner-scoped pair is `(partner_id, iban)`, declared with the party
-- model -- the role-scoped `(supplier_id, iban)` it replaces would have been created and dropped
-- inside the same upgrade run.
CREATE INDEX idx_supplier_bank_account_iban ON supplier_bank_account (iban);

COMMENT ON TABLE supplier_bank_account IS
    'IBANs this supplier has been seen to use. Turns a transaction counterparty_iban into a supplier, and makes an invoice quoting an unknown IBAN a payment-redirection warning.';

-- =============================================================================================
-- Our own bank accounts  [V92]
-- =============================================================================================
CREATE TABLE bank_account (
    id              BIGSERIAL PRIMARY KEY,
    shop_id         BIGINT       NOT NULL CONSTRAINT fk_bank_account_shop_id__shop_id
                                 REFERENCES shop_info (shop_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    iban            VARCHAR(34)  NOT NULL,
    bic             VARCHAR(11),
    bank_name       VARCHAR(255),
    account_holder  VARCHAR(255),
    ledger_account  VARCHAR(20),
    currency        VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP
);

CREATE UNIQUE INDEX bank_account_iban_unique ON bank_account (iban);
CREATE INDEX        idx_bank_account_shop_id ON bank_account (shop_id);

COMMENT ON TABLE bank_account IS
    'Our own bank accounts. Counterpart to supplier_bank_account, which holds other parties IBANs. Makes statement import resolvable and own-account transfers recognisable.';

-- =============================================================================================
-- invoice — the root of the supplier-invoice aggregate  [V86 + V88 + V90 + V93 + V94]
-- =============================================================================================
CREATE TABLE invoice (
    id                    BIGSERIAL PRIMARY KEY,
    source_document_id    BIGINT       CONSTRAINT fk_invoice_source_document_id__id
                                       REFERENCES documents (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    e_invoice_format      VARCHAR(20),
    extraction_source     VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    invoice_number        VARCHAR(100),
    issue_date            TIMESTAMP,
    due_date              TIMESTAMP,
    delivery_date         TIMESTAMP,
    currency              VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    buyer_reference       VARCHAR(100),
    seller_name           VARCHAR(255),
    seller_vat_id         VARCHAR(50),
    seller_tax_number     VARCHAR(50),
    seller_street         VARCHAR(255),
    seller_zip            VARCHAR(20),
    seller_city           VARCHAR(100),
    seller_country        VARCHAR(10),
    seller_phone          VARCHAR(50),
    seller_email          VARCHAR(255),
    payment_iban          VARCHAR(34),
    payment_reference     VARCHAR(255),
    payment_terms_text    VARCHAR(255),
    line_net_total        DECIMAL(19, 4),
    tax_exclusive_total   DECIMAL(19, 4),
    tax_total             DECIMAL(19, 4),
    tax_inclusive_total   DECIMAL(19, 4),
    amount_due            DECIMAL(19, 4),
    created_at            TIMESTAMP    NOT NULL,
    updated_at            TIMESTAMP,
    -- [V88] BT-3 (UNTDID 1001) and BT-25. '380' (commercial invoice) is the default so a
    -- hand-created invoice is an ordinary one unless stated otherwise; '381' is a credit note and
    -- '384' a corrected invoice. Storage accepts any code the parser finds. BT-25 is nullable with
    -- no default: an ordinary invoice refers to nothing, and '' would be a lie.
    invoice_type_code           VARCHAR(10) NOT NULL DEFAULT '380',
    preceding_invoice_reference VARCHAR(100),
    -- [V90] The resolved creditor, alongside the seller_* snapshot. Nullable and additive:
    -- seller_name/seller_vat_id remain what this document asserted and are never rewritten when
    -- master data is edited.
    supplier_id           BIGINT       CONSTRAINT fk_invoice_supplier_id__id
                                       REFERENCES supplier (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    -- [V93] When a human confirmed the content. Null means nobody has. It lives on the invoice, not
    -- on documents.processing_status, because an invoice may have no document at all — a bare
    -- XRechnung XML, or one entered by hand.
    reviewed_at           TIMESTAMP,
    -- [V94] Persisted residual: coalesce(amount_due, tax_inclusive_total) - SUM(allocations).
    -- Persisted rather than computed, because a SUM subquery per row makes the open-item list
    -- unindexable. NULL means "no residual known"; zero means settled. Different states — hence no
    -- DEFAULT. AllocationWriter writes it in the same transaction as every allocation.
    open_amount           DECIMAL(19, 4)
);

CREATE INDEX idx_invoice_invoice_number     ON invoice (invoice_number);
CREATE INDEX idx_invoice_issue_date         ON invoice (issue_date);
CREATE INDEX idx_invoice_seller_name        ON invoice (seller_name);
CREATE INDEX idx_invoice_source_document_id ON invoice (source_document_id);
CREATE INDEX idx_invoice_supplier_id        ON invoice (supplier_id);
CREATE INDEX idx_invoice_reviewed_at        ON invoice (reviewed_at);
CREATE INDEX idx_invoice_open_amount        ON invoice (open_amount);

COMMENT ON TABLE invoice IS
    'Supplier invoice, EN 16931 aligned. source_document_id is nullable because XRechnung may arrive as bare XML with no PDF.';

-- ---------------------------------------------------------------------------------------------
-- invoice_line — EN 16931 BG-25  [V86]
-- ---------------------------------------------------------------------------------------------
CREATE TABLE invoice_line (
    id                 BIGSERIAL PRIMARY KEY,
    invoice_id         BIGINT         NOT NULL CONSTRAINT fk_invoice_line_invoice_id__id
                                      REFERENCES invoice (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    line_id            VARCHAR(50)    NOT NULL,
    item_name          VARCHAR(500)   NOT NULL,
    item_description   TEXT,
    quantity           DECIMAL(19, 4) NOT NULL,
    unit_code          VARCHAR(10)    NOT NULL,
    net_unit_price     DECIMAL(19, 4) NOT NULL,
    line_net_amount    DECIMAL(19, 4) NOT NULL,
    vat_category_code  VARCHAR(5)     NOT NULL,
    vat_rate           DECIMAL(5, 2)  NOT NULL,
    seller_item_id     VARCHAR(100),
    buyer_item_id      VARCHAR(100),
    item_standard_id   VARCHAR(100)
);

CREATE INDEX idx_invoice_line_invoice_id ON invoice_line (invoice_id);
CREATE UNIQUE INDEX idx_invoice_line_invoice_id_line_id ON invoice_line (invoice_id, line_id);

COMMENT ON TABLE invoice_line IS
    'EN 16931 BG-25 invoice line. net_unit_price carries four decimals on purpose (BT-146 unit prices are commonly sub-cent).';

-- ---------------------------------------------------------------------------------------------
-- invoice_vat_breakdown — EN 16931 BG-23, one row per (category, rate)  [V86]
-- ---------------------------------------------------------------------------------------------
CREATE TABLE invoice_vat_breakdown (
    id                      BIGSERIAL PRIMARY KEY,
    invoice_id              BIGINT         NOT NULL CONSTRAINT fk_invoice_vat_breakdown_invoice_id__id
                                           REFERENCES invoice (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    vat_category_code       VARCHAR(5)     NOT NULL,
    vat_rate                DECIMAL(5, 2)  NOT NULL,
    taxable_amount          DECIMAL(19, 4) NOT NULL,
    vat_amount              DECIMAL(19, 4) NOT NULL,
    exemption_reason_code   VARCHAR(50),
    exemption_reason_text   VARCHAR(500),
    description             VARCHAR(500)
);

CREATE INDEX idx_invoice_vat_breakdown_invoice_id ON invoice_vat_breakdown (invoice_id);

COMMENT ON TABLE invoice_vat_breakdown IS
    'EN 16931 BG-23 VAT breakdown; replaces document_invoice_metadata.tax_line_items_json. The DATEV AP export emits one ledger entry per row, taking its booking text from the description column (not an EN 16931 term).';

-- ---------------------------------------------------------------------------------------------
-- invoice_allowance_charge — EN 16931 BG-20 / BG-21, document level  [V86]
-- ---------------------------------------------------------------------------------------------
CREATE TABLE invoice_allowance_charge (
    id                 BIGSERIAL PRIMARY KEY,
    invoice_id         BIGINT         NOT NULL CONSTRAINT fk_invoice_allowance_charge_invoice_id__id
                                      REFERENCES invoice (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    is_charge          BOOLEAN        NOT NULL,
    amount             DECIMAL(19, 4) NOT NULL,
    base_amount        DECIMAL(19, 4),
    percentage         DECIMAL(5, 2),
    reason_code        VARCHAR(50),
    reason_text        VARCHAR(500),
    vat_category_code  VARCHAR(5)     NOT NULL,
    vat_rate           DECIMAL(5, 2)  NOT NULL
);

CREATE INDEX idx_invoice_allowance_charge_invoice_id ON invoice_allowance_charge (invoice_id);

COMMENT ON TABLE invoice_allowance_charge IS
    'EN 16931 BG-20 allowance / BG-21 charge at document level. is_charge distinguishes the two.';

-- ---------------------------------------------------------------------------------------------
-- invoice_booking — OUR bookkeeping decision, 1:1 with invoice  [V86]
-- ---------------------------------------------------------------------------------------------
CREATE TABLE invoice_booking (
    id                     BIGSERIAL PRIMARY KEY,
    invoice_id             BIGINT         NOT NULL CONSTRAINT fk_invoice_booking_invoice_id__id
                                          REFERENCES invoice (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    account_no             VARCHAR(20),
    bp_account_no          VARCHAR(20),
    bu_code                VARCHAR(10),
    booking_text           VARCHAR(60),
    cost_category_id       VARCHAR(20),
    cost_category_id2      VARCHAR(20),
    cost_amount            DECIMAL(19, 4),
    type_of_receivable     VARCHAR(50),
    payment_dunning_block  BOOLEAN        NOT NULL DEFAULT FALSE,
    paid_at                TIMESTAMP
);

CREATE UNIQUE INDEX idx_invoice_booking_invoice_id ON invoice_booking (invoice_id);

COMMENT ON TABLE invoice_booking IS
    'How WE book an invoice (Gegenkonto, Kreditor, BU-Schluessel, Kostenstellen), 1:1 with invoice. Kept separate from supplier-asserted data so an edit form cannot null parsed invoice fields. This is what the DATEV Eingangsrechnungen export reads.';

-- =============================================================================================
-- bank_statement — the root of the bank-statement aggregate  [V86 + V91 + V92]
-- =============================================================================================
CREATE TABLE bank_statement (
    id                     BIGSERIAL PRIMARY KEY,
    source_document_id     BIGINT       CONSTRAINT fk_bank_statement_source_document_id__id
                                        REFERENCES documents (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    format                 VARCHAR(20)  NOT NULL DEFAULT 'manual',
    account_iban           VARCHAR(34),
    account_bic            VARCHAR(11),
    bank_name              VARCHAR(255),
    account_number         VARCHAR(50),
    statement_number       VARCHAR(50),
    legal_sequence_number  VARCHAR(50),
    statement_date         TIMESTAMP,
    period_start           TIMESTAMP,
    period_end             TIMESTAMP,
    opening_balance        DECIMAL(19, 4),
    closing_balance        DECIMAL(19, 4),
    currency               VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    created_at             TIMESTAMP    NOT NULL,
    updated_at             TIMESTAMP,
    -- [V91] Null means no earlier statement for this account overlaps this one's period. A non-null
    -- value points at the earliest already-stored statement whose period overlaps. Overlaps are
    -- stored verbatim rather than merged: a bank export can legitimately repeat a period, and
    -- MT940 has no per-entry identifier, so dropping on that signal would lose money.
    overlapping_statement_id BIGINT     CONSTRAINT fk_bank_statement_overlapping_statement_id__id
                                        REFERENCES bank_statement (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    -- [V92] OUR decision about a statement the bank asserted. Nullable on purpose, and NULL is a
    -- normal state rather than an error: the statement is written on upload with the bank-asserted
    -- IBAN in account_iban, and resolution to an account happens afterwards. NULL is a work item.
    bank_account_id        BIGINT       CONSTRAINT fk_bank_statement_bank_account_id__id
                                        REFERENCES bank_account (id) ON DELETE SET NULL ON UPDATE RESTRICT
);

CREATE INDEX idx_bank_statement_source_document_id       ON bank_statement (source_document_id);
CREATE INDEX idx_bank_statement_account_iban             ON bank_statement (account_iban);
CREATE INDEX idx_bank_statement_statement_date           ON bank_statement (statement_date);
CREATE INDEX idx_bank_statement_overlapping_statement_id ON bank_statement (overlapping_statement_id);
CREATE INDEX idx_bank_statement_bank_account_id          ON bank_statement (bank_account_id);

COMMENT ON TABLE bank_statement IS
    'Bank statement header. source_document_id is nullable because a statement may be entered manually or arrive without a stored file; format records how it arrived (camt053 | mt940 | pdf | manual).';

-- ---------------------------------------------------------------------------------------------
-- bank_statement_transaction — one CAMT entry  [V86 + V91 + V94]
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bank_statement_transaction (
    id                          BIGSERIAL PRIMARY KEY,
    statement_id                BIGINT         NOT NULL CONSTRAINT fk_bank_statement_transaction_statement_id__id
                                               REFERENCES bank_statement (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    booking_date                TIMESTAMP      NOT NULL,
    value_date                  TIMESTAMP      NOT NULL,
    amount                      DECIMAL(19, 4) NOT NULL,
    currency                    VARCHAR(3)     NOT NULL DEFAULT 'EUR',
    credit_debit                VARCHAR(6)     NOT NULL,
    status                      VARCHAR(20),
    bank_tx_domain              VARCHAR(20),
    bank_tx_family              VARCHAR(20),
    bank_tx_subfamily           VARCHAR(20),
    account_servicer_reference  VARCHAR(100),
    end_to_end_id               VARCHAR(100),
    mandate_id                  VARCHAR(100),
    creditor_id                 VARCHAR(100),
    counterparty_name           VARCHAR(255),
    counterparty_iban           VARCHAR(34),
    counterparty_bic            VARCHAR(11),
    remittance_unstructured     TEXT,
    remittance_structured_ref   VARCHAR(100),
    reversal_indicator          BOOLEAN        NOT NULL DEFAULT FALSE,
    raw_entry                   TEXT,
    -- [V91] Null means "not seen before". A non-null value points at the transaction this one
    -- repeats, and nothing is deleted: a matcher MUST filter on this column or it will book the
    -- same payment against the same invoice more than once.
    duplicate_of_transaction_id BIGINT         CONSTRAINT fk_bank_statement_transaction_duplicate_of_transaction_id__id
                                               REFERENCES bank_statement_transaction (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    -- [V94] Persisted residual: amount - SUM(allocations), feeding the assignment queue.
    -- NULL means "no residual known"; zero means fully assigned.
    open_amount                 DECIMAL(19, 4),
    -- [V97] When the reversal sweep last drew a conclusion about this entry -- NOT when it voided
    -- something. Voiding is naturally idempotent (after the first sweep a reversed entry has no
    -- allocations left), which breaks the case that matters: an operator may decide the return was
    -- itself the error and re-allocate the original by hand, and without this marker the next
    -- nightly sweep would void their work again, silently, forever. A reversal that paired with
    -- nothing is stamped too -- "looked and found nothing" is a conclusion, not a failure.
    --
    -- Not indexed: the sweep filters on reversal_indicator = TRUE first, a small slice of the
    -- table, and an unused index is a cost paid on every statement import.
    --
    -- Never back-filled. NULL means "never swept", which is exactly true of every row this table
    -- starts life with. (V97 added this as an ALTER on the upgrade path; both tables it touched are
    -- created in this file, so its two columns are folded into the CREATE TABLEs instead.)
    reversal_processed_at       TIMESTAMP
);

CREATE INDEX idx_bank_statement_transaction_statement_id      ON bank_statement_transaction (statement_id);
CREATE INDEX idx_bank_statement_transaction_value_date        ON bank_statement_transaction (value_date);
CREATE INDEX idx_bank_statement_transaction_counterparty_iban ON bank_statement_transaction (counterparty_iban);
CREATE INDEX idx_bank_statement_transaction_duplicate_of_transaction_id ON bank_statement_transaction (duplicate_of_transaction_id);
CREATE INDEX idx_bank_statement_transaction_open_amount       ON bank_statement_transaction (open_amount);

COMMENT ON TABLE bank_statement_transaction IS
    'One statement entry; replaces document_bank_statement_metadata.transactions_json. amount is always positive and credit_debit carries the direction, matching ISO 20022 CdtDbtInd. raw_entry is an additive escape hatch for unmodelled CAMT fields — no business logic reads it.';

-- =============================================================================================
-- Outgoing invoices: debtor, sales invoice and its children, the number counter  [V99 + V101 + V105]
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- debtor — the AR mirror of `supplier`, and like it the ROLE only.
--
-- Identity — name, legal_name, vat_id, tax_number, the address block, email and phone — is NOT
-- declared here for the same reason it is absent from `supplier`: it lives on `business_partner`,
-- added later in this chain, and creating ten columns here only to drop them again in the same
-- upgrade run would be pure churn.
--
-- Separate from `customers` because that table's GDPR erasure lifecycle collides with the ten-year
-- §147 AO retention of an invoice recipient. customer_id links the two and SET NULLs on erasure;
-- the invoice's own buyer_* snapshot is what survives.
--
-- debtor_no is nullable and never generated on insert: the Personenkonto namespace belongs to the
-- Steuerberater. It is NOT unique-constrained here, because a released number is retired by policy
-- rather than by the schema, and a partial history import may legitimately carry duplicates.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE debtor (
    id                         BIGSERIAL PRIMARY KEY,
    shop_id                    BIGINT       NOT NULL CONSTRAINT fk_debtor_shop_id__shop_id
                                            REFERENCES shop_info (shop_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    customer_id                UUID         CONSTRAINT fk_debtor_customer_id__customer_id
                                            REFERENCES customers (customer_id) ON DELETE SET NULL ON UPDATE RESTRICT,
    debtor_no                  VARCHAR(20),
    default_revenue_account_no VARCHAR(20),
    default_bu_code            VARCHAR(10),
    payment_terms_days         INTEGER,
    is_active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    notes                      TEXT,
    created_at                 TIMESTAMP    NOT NULL,
    updated_at                 TIMESTAMP,
    -- [V105] How this debtor wants an invoice delivered. 'NONE' so every existing and hand-created
    -- debtor keeps the pre-e-invoice behaviour.
    einvoice_format            VARCHAR(20)  NOT NULL DEFAULT 'NONE'
);

CREATE INDEX idx_debtor_shop_id     ON debtor (shop_id);
CREATE INDEX idx_debtor_debtor_no   ON debtor (debtor_no);
CREATE INDEX idx_debtor_customer_id ON debtor (customer_id);

COMMENT ON TABLE debtor IS
    'Customers we invoice. AR mirror of supplier; separate from customers so GDPR erasure cannot delete an invoice recipient held under section 147 AO.';

-- ---------------------------------------------------------------------------------------------
-- sales_invoice — an invoice WE issued.
--
-- Deliberately not a `direction` column on `invoice`: that table holds what a counterparty
-- asserted, editable, with our decisions in invoice_booking. Here every field is ours, binding
-- under §14 UStG and immutable once issued, while sales_invoice_booking stays editable. One table
-- would make immutability conditional on a column value.
--
-- invoice_number is NULL until issued and unique once set. That is what keeps the sequence gapless:
-- a discarded draft consumed no number.
--
-- bill_id is ON DELETE RESTRICT, not SET NULL: losing the link would strand an invoice claiming a
-- sale it can no longer point at.
--
-- The tse_* block is the ORIGINAL bill's signature, snapshotted at issue. §6 KassenSichV requires
-- the Beleg for a sale to disclose it, and when the guest leaves holding the Rechnung, the Rechnung
-- is that document. Snapshotted rather than joined so a reprint shows the same block forever.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE sales_invoice (
    id                          BIGSERIAL PRIMARY KEY,
    shop_id                     BIGINT       NOT NULL CONSTRAINT fk_sales_invoice_shop_id__shop_id
                                             REFERENCES shop_info (shop_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    invoice_number              VARCHAR(50),
    status                      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    revenue_origin              VARCHAR(20)  NOT NULL,
    invoice_type_code           VARCHAR(10)  NOT NULL DEFAULT '380',
    preceding_sales_invoice_id  BIGINT       CONSTRAINT fk_sales_invoice_preceding__id
                                             REFERENCES sales_invoice (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    bill_id                     BIGINT       CONSTRAINT fk_sales_invoice_bill_id__id
                                             REFERENCES bill (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    debtor_id                   BIGINT       CONSTRAINT fk_sales_invoice_debtor_id__id
                                             REFERENCES debtor (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    buyer_name                  VARCHAR(255) NOT NULL,
    buyer_vat_id                VARCHAR(50),
    buyer_street                VARCHAR(255),
    buyer_zip                   VARCHAR(20),
    buyer_city                  VARCHAR(100),
    buyer_country               VARCHAR(10),
    buyer_email                 VARCHAR(255),
    seller_name                 VARCHAR(255),
    seller_vat_id               VARCHAR(50),
    seller_tax_number           VARCHAR(50),
    seller_street               VARCHAR(255),
    seller_zip                  VARCHAR(20),
    seller_city                 VARCHAR(100),
    seller_country              VARCHAR(10),
    issue_date                  TIMESTAMP    NOT NULL,
    service_date                TIMESTAMP    NOT NULL,
    due_date                    TIMESTAMP,
    currency                    VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    line_net_total              DECIMAL(19, 4),
    -- BT-107 / BT-108: Σ document-level allowances / charges (sales_invoice_allowance_charge). NULL
    -- when the invoice has none, so an invoice without a promotion states no zero totals.
    allowance_total             DECIMAL(19, 4),
    charge_total                DECIMAL(19, 4),
    tax_exclusive_total         DECIMAL(19, 4),
    tax_total                   DECIMAL(19, 4),
    tax_inclusive_total         DECIMAL(19, 4),
    prepaid_amount              DECIMAL(19, 4),
    amount_due                  DECIMAL(19, 4),
    open_amount                 DECIMAL(19, 4),
    payment_terms_text          VARCHAR(255),
    payment_reference           VARCHAR(255),
    notes                       TEXT,
    tse_serial_number           VARCHAR(100),
    tse_transaction_number      BIGINT,
    tse_signature_counter       BIGINT,
    tse_signature               VARCHAR(1000),
    tse_signature_algorithm     VARCHAR(100),
    tse_log_time_format         VARCHAR(31),
    tse_process_type            VARCHAR(100),
    tse_start_time              TIMESTAMP,
    tse_end_time                TIMESTAMP,
    tse_timestamp               BIGINT,
    tse_start_timestamp         BIGINT,
    tse_qr_code_payload         TEXT,
    issued_at                   TIMESTAMP,
    issued_by                   UUID         CONSTRAINT fk_sales_invoice_issued_by__employee_id
                                             REFERENCES employees (employee_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    cancelled_at                TIMESTAMP,
    cancelled_by                UUID         CONSTRAINT fk_sales_invoice_cancelled_by__employee_id
                                             REFERENCES employees (employee_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    cancellation_reason         VARCHAR(500),
    document_id                 BIGINT       CONSTRAINT fk_sales_invoice_document_id__id
                                             REFERENCES documents (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    created_at                  TIMESTAMP    NOT NULL,
    updated_at                  TIMESTAMP
);

CREATE UNIQUE INDEX sales_invoice_invoice_number_unique ON sales_invoice (invoice_number);
CREATE INDEX        idx_sales_invoice_shop_id           ON sales_invoice (shop_id);
CREATE INDEX        idx_sales_invoice_status            ON sales_invoice (status);
CREATE INDEX        idx_sales_invoice_bill_id           ON sales_invoice (bill_id);
CREATE INDEX        idx_sales_invoice_debtor_id         ON sales_invoice (debtor_id);
CREATE INDEX        idx_sales_invoice_issue_date        ON sales_invoice (issue_date);
CREATE INDEX        idx_sales_invoice_open_amount       ON sales_invoice (open_amount);

-- One LIVE invoice per bill. Partial, so a cancelled invoice frees the slot and a corrected one can
-- be issued. Without it a guest holds both a Kassenbeleg and an invoice for the same meal and can
-- deduct both. Declared only here: Exposed cannot express a partial unique index, and the parity
-- test only checks that Exposed-declared indexes EXIST in Postgres, so an extra one is fine.
CREATE UNIQUE INDEX sales_invoice_one_live_per_bill
    ON sales_invoice (bill_id)
    WHERE bill_id IS NOT NULL AND status <> 'CANCELLED';

COMMENT ON TABLE sales_invoice IS
    'Invoices we issued. Immutable once ISSUED; the only exit is a 381 Gutschrift. Carries the originating bills TSE signature block per section 6 KassenSichV.';

-- ---------------------------------------------------------------------------------------------
-- sales_invoice_line — item name and price are SNAPSHOTS. Renaming a product must not rewrite last
-- year's invoice; source_order_item_id is audit only and feeds no displayed value.
--
-- The FK target is t_order_item, which is what OrderItemTable actually maps to — not `order_item`.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE sales_invoice_line (
    id                   BIGSERIAL PRIMARY KEY,
    sales_invoice_id     BIGINT         NOT NULL CONSTRAINT fk_sales_invoice_line_invoice__id
                                        REFERENCES sales_invoice (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    line_no              INTEGER        NOT NULL,
    item_name            VARCHAR(500)   NOT NULL,
    item_description     TEXT,
    quantity             DECIMAL(19, 4) NOT NULL,
    unit_code            VARCHAR(10)    NOT NULL DEFAULT 'C62',
    net_unit_price       DECIMAL(19, 4) NOT NULL,
    line_net_amount      DECIMAL(19, 4) NOT NULL,
    vat_rate             DECIMAL(5, 2)  NOT NULL,
    vat_category_code    VARCHAR(5)     NOT NULL DEFAULT 'S',
    vat_amount           DECIMAL(19, 4) NOT NULL,
    line_gross_amount    DECIMAL(19, 4) NOT NULL,
    source_order_item_id BIGINT         CONSTRAINT fk_sales_invoice_line_order_item__id
                                        REFERENCES t_order_item (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    -- [V101] The gross unit price the OPERATOR typed, stored verbatim. Without it, an editor
    -- reopening a draft rebuilt the unit price as line_gross_amount / quantity rounded to scale 2:
    -- quantity 3 at a typed 3.3333 stores gross 10.00, comes back as 3.33 and re-saves as 9.99 — a
    -- silent cent drop on every open-and-save.
    gross_unit_price     DECIMAL(19, 4) NOT NULL DEFAULT 0
);

CREATE INDEX        idx_sales_invoice_line_invoice_id ON sales_invoice_line (sales_invoice_id);
CREATE UNIQUE INDEX sales_invoice_line_no_unique      ON sales_invoice_line (sales_invoice_id, line_no);

-- ---------------------------------------------------------------------------------------------
-- sales_invoice_vat_breakdown — BG-23, one row per (category, rate).
--
-- For a POS-derived invoice these are COPIED from the bill's fiscal split, never recomputed: a
-- recomputation can land a cent away from the printed receipt.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE sales_invoice_vat_breakdown (
    id                    BIGSERIAL PRIMARY KEY,
    sales_invoice_id      BIGINT         NOT NULL CONSTRAINT fk_sales_invoice_vat_invoice__id
                                         REFERENCES sales_invoice (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    vat_category_code     VARCHAR(5)     NOT NULL,
    vat_rate              DECIMAL(5, 2)  NOT NULL,
    taxable_amount        DECIMAL(19, 4) NOT NULL,
    vat_amount            DECIMAL(19, 4) NOT NULL,
    exemption_reason_text VARCHAR(500)
);

CREATE INDEX idx_sales_invoice_vat_invoice_id ON sales_invoice_vat_breakdown (sales_invoice_id);

-- ---------------------------------------------------------------------------------------------
-- sales_invoice_allowance_charge — EN 16931 BG-20 allowance / BG-21 charge, document level.
--
-- A from-bill invoice carries one allowance per promotion (Aktion) the till took off, per VAT key.
-- The lines stay at the price charged; the VAT breakdown, copied from the bill, already absorbed the
-- allowance. amount is BT-92 and NET, as EN 16931 states it; vat_amount and gross_amount keep the
-- till's VAT-inclusive deduction (net + vat = gross, VAT by subtraction) for the gross-basis PDF.
-- Allowances only today; is_charge keeps the EN 16931 shape. Negated on a 381 Gutschrift like the
-- lines, so there is deliberately no sign constraint here.
--
-- bill_promotion_id is audit only and feeds no printed value (SET NULL, like source_order_item_id).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE sales_invoice_allowance_charge (
    id                BIGSERIAL PRIMARY KEY,
    sales_invoice_id  BIGINT         NOT NULL CONSTRAINT fk_sales_invoice_allowance_charge_sales_invoice_id__id
                                     REFERENCES sales_invoice (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    position          INTEGER        NOT NULL,
    is_charge         BOOLEAN        NOT NULL,
    amount            DECIMAL(19, 4) NOT NULL,
    base_amount       DECIMAL(19, 4),
    percentage        DECIMAL(5, 2),
    vat_category_code VARCHAR(5)     NOT NULL,
    vat_rate          DECIMAL(5, 2)  NOT NULL,
    vat_amount        DECIMAL(19, 4) NOT NULL,
    gross_amount      DECIMAL(19, 4) NOT NULL,
    reason_text       VARCHAR(500),
    reason_code       VARCHAR(10),
    bill_promotion_id BIGINT         CONSTRAINT fk_sales_invoice_allowance_charge_bill_promotion_id__id
                                     REFERENCES bill_promotion (id) ON DELETE SET NULL ON UPDATE RESTRICT
);

CREATE INDEX        idx_sales_invoice_allowance_charge_invoice_id ON sales_invoice_allowance_charge (sales_invoice_id);
CREATE UNIQUE INDEX sales_invoice_allowance_charge_position_unique ON sales_invoice_allowance_charge (sales_invoice_id, position);

-- ---------------------------------------------------------------------------------------------
-- sales_invoice_booking — OUR decisions, the layer that stays editable while the invoice freezes.
--
-- bp_account_no is the Debitorenkonto ACTUALLY used, frozen here rather than read live from debtor:
-- master data is editable, a posting already sent to the Steuerberater is not.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE sales_invoice_booking (
    id                 BIGSERIAL PRIMARY KEY,
    sales_invoice_id   BIGINT       NOT NULL CONSTRAINT fk_sales_invoice_booking_invoice__id
                                    REFERENCES sales_invoice (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    bp_account_no      VARCHAR(20),
    revenue_account_no VARCHAR(20),
    bu_code            VARCHAR(10),
    cost_center        VARCHAR(20),
    booking_text       VARCHAR(255),
    posted_at          TIMESTAMP
);

CREATE UNIQUE INDEX sales_invoice_booking_invoice_unique ON sales_invoice_booking (sales_invoice_id);

-- ---------------------------------------------------------------------------------------------
-- ar_number_sequence — the §14 UStG counter, one row per (prefix, year).
--
-- A row rather than a count() and rather than an in-process tracker: SELECT ... FOR UPDATE holds
-- across processes, and the increment rolls back with a failed issuance so no hole is left.
--
-- next_value is what the NEXT call hands out.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ar_number_sequence (
    id         BIGSERIAL PRIMARY KEY,
    prefix     VARCHAR(10) NOT NULL,
    year       INTEGER     NOT NULL,
    next_value BIGINT      NOT NULL
);

CREATE UNIQUE INDEX ar_number_sequence_prefix_year_unique ON ar_number_sequence (prefix, year);

-- =============================================================================================
-- payment_allocation  [V94 + V100 + V103]
--
-- One row means *this much of this payment went to that*.
--
-- Both sides are typed: a small kind string plus one nullable FK per case, never a polymorphic id.
-- That keeps referential integrity real, at the cost of several mostly-null columns — the right
-- trade for money.
--
-- contract_instalment_id is a plain nullable BIGINT with NO foreign key here: the contracts
-- migration creates contract_instalment and adds the constraint. A stub table to hang an FK on now
-- would be worse than a missing constraint, because it would look real.
--
-- Nothing here ever writes to kassenbuch_header: those rows are TSE-signed and lockable, so an
-- assignment has to be an additive row beside them rather than a mutation of them.
-- =============================================================================================
CREATE TABLE payment_allocation (
    id                           BIGSERIAL PRIMARY KEY,
    source_kind                  VARCHAR(20)    NOT NULL,
    bank_transaction_id          BIGINT         CONSTRAINT fk_payment_allocation_bank_transaction_id__id
                                                REFERENCES bank_statement_transaction (id)
                                                ON DELETE CASCADE ON UPDATE RESTRICT,
    kassenbuch_header_id         BIGINT         CONSTRAINT fk_payment_allocation_kassenbuch_header_id__id
                                                REFERENCES kassenbuch_header (id)
                                                ON DELETE CASCADE ON UPDATE RESTRICT,
    target_kind                  VARCHAR(30)    NOT NULL,
    invoice_id                   BIGINT         CONSTRAINT fk_payment_allocation_invoice_id__id
                                                REFERENCES invoice (id)
                                                ON DELETE CASCADE ON UPDATE RESTRICT,
    contract_instalment_id       BIGINT,
    counter_kassenbuch_header_id BIGINT         CONSTRAINT fk_payment_allocation_counter_kassenbuch_header_id__id
                                                REFERENCES kassenbuch_header (id)
                                                ON DELETE SET NULL ON UPDATE RESTRICT,
    counter_bank_account_id      BIGINT         CONSTRAINT fk_payment_allocation_counter_bank_account_id__id
                                                REFERENCES bank_account (id)
                                                ON DELETE SET NULL ON UPDATE RESTRICT,
    amount                       DECIMAL(19, 4) NOT NULL,
    account_no                   VARCHAR(20),
    booking_text                 VARCHAR(60),
    matched_by                   VARCHAR(20)    NOT NULL,
    rule_id                      BIGINT,
    confidence                   DECIMAL(5, 4),
    note                         TEXT,
    created_at                   TIMESTAMP      NOT NULL,
    created_by                   VARCHAR(64),
    -- [V100] AR side of the allocation engine: a payment can settle an outgoing invoice. RESTRICT,
    -- not CASCADE: a draft never has allocations and an issued invoice is immutable and never
    -- deleted — so a RESTRICT violation means a bug and must fail loudly.
    sales_invoice_id             BIGINT         CONSTRAINT fk_payment_allocation_sales_invoice_id__id
                                                REFERENCES sales_invoice (id)
                                                ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- [V103] The third allocation source. BANK_TRANSACTION and CASH_BOOK_ENTRY each carry their own
    -- FK; a till receipt is neither — the money did not arrive on a bank statement, and a card
    -- clearing has no place in a cash book — so it carries its own.
    bill_id                      BIGINT         CONSTRAINT fk_payment_allocation_bill_id__id
                                                REFERENCES bill (id)
                                                ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- [V97] The MatchSignal name that produced an accepted suggestion; NULL for a manual
    -- allocation, which claims no signal. matched_by already separates MANUAL from
    -- SUGGESTION_ACCEPTED, which answers "are suggestions used at all"; it cannot answer which of
    -- the seven signals earn their place, because the signal itself is stored nowhere.
    --
    -- Deliberately NOT rule_id: that column and confidence stay reserved for a future auto-rule
    -- engine, and writing a signal name into a column meaning "which rule row" would repeat the
    -- category error supplier.creditor_no invited.
    --
    -- VARCHAR(40): the longest constant today is SUPPLIER_NAME_AMOUNT_DATE at 24 characters.
    --
    -- Not indexed: the telemetry endpoint groups over a table read once by whoever asks, not per
    -- request path. (V97 added this as an ALTER; folded into the CREATE TABLE here -- see the note
    -- on bank_statement_transaction.reversal_processed_at.)
    matched_signal               VARCHAR(40)
);

CREATE INDEX idx_payment_allocation_bank_transaction_id    ON payment_allocation (bank_transaction_id);
CREATE INDEX idx_payment_allocation_kassenbuch_header_id   ON payment_allocation (kassenbuch_header_id);
CREATE INDEX idx_payment_allocation_invoice_id             ON payment_allocation (invoice_id);
CREATE INDEX idx_payment_allocation_contract_instalment_id ON payment_allocation (contract_instalment_id);
CREATE INDEX idx_payment_allocation_sales_invoice_id       ON payment_allocation (sales_invoice_id);
CREATE INDEX idx_payment_allocation_bill_id                ON payment_allocation (bill_id);

COMMENT ON TABLE payment_allocation IS
    'This much of this payment went to that. Typed source (bank transaction or cash-book entry) and typed target (invoice, contract instalment, cash transit, internal transfer, or a bare account). Additive beside kassenbuch_header, which is TSE-signed and never mutated.';

-- =============================================================================================
-- Columns on tables that PREDATE V81 — these stay ALTERs by construction.
-- `documents`, `payment_type`, `payment`, `bill`, `zbon` and `kassenbuch_line_item` are all part of
-- the base schema, so an upgrading install already holds their rows.
-- =============================================================================================

-- [V89] Index documents.processing_status for the adminApp work queue.
--
-- Composite, not a plain btree on processing_status alone: the column is low-cardinality (eight
-- ProcessingStatus constants, and in practice almost every row sits at NONE or COMPLETED), so on
-- its own it would rarely beat a sequential scan and would mostly cost write amplification. The
-- queue's query is not "status = X" — DocumentRepositoryImpl.buildSearchQuery ANDs the status
-- predicate with is_active and usually a date range, then orders by uploaded_at DESC with
-- LIMIT/OFFSET. (processing_status, uploaded_at) lets Postgres seek the status bucket and read it
-- already sorted. Declared in Exposed too (DocumentTable.init), so the H2 test schema matches.
CREATE INDEX IF NOT EXISTS idx_documents_processing_status_uploaded_at
    ON documents (processing_status, uploaded_at);

-- [V102] A bill can be settled "auf Rechnung" — money the customer owes rather than money received.
--
-- `payment_type.creates_receivable` is master data: which tenders mean "the customer will pay
-- later". Editable, and editing it must change only future settlements.
--
-- `payment.creates_receivable` is the FROZEN SNAPSHOT, copied from the type at settlement and never
-- read live. Reclassifying an already-settled bill after the fact is exactly the "money received
-- twice" defect this removes: the DSFinV-K export, the Z-Bon and the AR origin all read the
-- payment's snapshot.
--
-- Both default false, so this changes no existing row's meaning and no export moves.
ALTER TABLE payment_type
    ADD COLUMN creates_receivable BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE payment
    ADD COLUMN creates_receivable BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN payment.creates_receivable IS
    'Frozen at settlement from payment_type.creates_receivable. Never read live: editing the type must not reclassify a settled bill.';

-- [V103] A guest can settle an open receivable at the counter, in cash or by card.
--
-- The clearing is a Bill — a real Kassenbeleg, so it is TSE-signed and belongs to a Z-Bon like any
-- receipt. clears_sales_invoice_id is what marks it: every aggregation that must treat a clearing
-- differently tests this column rather than inferring from "a bill with no order items", a
-- definition anything else that ever creates an itemless bill would silently inherit.
ALTER TABLE bill
    ADD COLUMN clears_sales_invoice_id BIGINT
        CONSTRAINT fk_bill_clears_sales_invoice_id__id
        REFERENCES sales_invoice (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE INDEX idx_bill_clears_sales_invoice_id ON bill (clears_sales_invoice_id);

-- No COMMENT ON COLUMN here: `bill` is now in the parity test's compared set (Task 6, 2026-09-29),
-- and a stored column comment makes Exposed emit a pending `COMMENT ON COLUMN ... IS NULL`, failing
-- parity — same reason V83/V85 give for their own tables. The why for clears_sales_invoice_id is
-- the `--` comment above the ALTER instead.

-- [V104] A receivable cleared at the till is money for a sale already booked. It must be reported
-- on the closing separately from revenue (a Forderungsausgleich), so the operator sees the drawer
-- rise without it being counted as new turnover. Default 0 so every historical closing reads as
-- "no receivables cleared", which is what they were. (Bare `0`, not `0.00`: `zbon` is also in the
-- parity test's compared set, and Exposed's `.default(BigDecimal.ZERO)` renders as `0` — `0.00`
-- round-trips through Postgres as a distinct default literal and fails parity.)
ALTER TABLE zbon
    ADD COLUMN total_receivable_cleared DECIMAL(19, 4) NOT NULL DEFAULT 0;

-- [V104] KassenbuchLineKind.RECEIVABLE_SETTLEMENT (21 chars) does not fit the original VARCHAR(20).
-- Widen to 30, matching payment_allocation.target_kind, with headroom for future kinds.
ALTER TABLE kassenbuch_line_item
    ALTER COLUMN kind TYPE VARCHAR(30);
