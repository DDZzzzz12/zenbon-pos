-- Business partner model. See docs/superpowers/specs/2026-09-02-business-partner-model-design.md
--
-- Grouped from three separate merges (business_partner, the identity drops that followed it, and
-- the debtor-side uniqueness that mirrored them) into one file, because the three only ever ran as
-- one upgrade -- nothing has shipped them. The 22 identity columns those merges dropped are not
-- dropped here: `V81__schema_documents_and_invoicing.sql` no longer declares them, so the chain
-- creates the party model directly instead of creating identity twice and discarding one copy.
--
-- There is NO data migration here, on purpose. Nothing is deployed, so the schema is created in its
-- final shape rather than carrying rows across. The only databases holding supplier or debtor rows
-- are developer machines, and re-creating one of those is cheaper -- and far less error-prone --
-- than correlating every new party back to its source row and proving that correlation right.

-- The guard below is normally trivially true and still earns its place.
--
-- On a real upgrade from v1.0.10 these three tables are CREATED, empty, by V81 a few files earlier
-- in this same chain run, so there is nothing for it to find. What it protects is the other case: a
-- developer database that already ran an earlier shape of this chain and holds seeded rows. There,
-- `ADD COLUMN partner_id NOT NULL` would fail with a Postgres constraint message naming a column
-- and nothing the reader can act on -- and such a database also still carries the identity columns
-- V81 no longer creates and nothing drops any more. The instruction is to re-create it, and the
-- guard is what says so.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM supplier)
       OR EXISTS (SELECT 1 FROM debtor)
       OR EXISTS (SELECT 1 FROM supplier_bank_account) THEN
        RAISE EXCEPTION
            'business_partner ships no backfill: supplier/debtor/supplier_bank_account must be empty. '
            'Nothing is deployed, so re-create this database rather than migrating it. '
            'See docs/superpowers/specs/2026-09-02-business-partner-model-design.md';
    END IF;
END $$;

-- `shop_info`'s primary key is `shop_id`, not `id` -- it is a LongIdTable with a renamed id column,
-- and every other migration that points at it names `shop_id` too.
CREATE TABLE business_partner (
    id                       BIGSERIAL PRIMARY KEY,
    shop_id                  BIGINT       NOT NULL CONSTRAINT fk_business_partner_shop_id__shop_id
                                                REFERENCES shop_info (shop_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    name                     VARCHAR(255) NOT NULL,
    legal_name               VARCHAR(255),
    vat_id                   VARCHAR(50),
    tax_number               VARCHAR(50),
    street                   VARCHAR(255),
    zip                      VARCHAR(20),
    city                     VARCHAR(100),
    country                  VARCHAR(10),
    email                    VARCHAR(255),
    phone                    VARCHAR(50),
    -- NOT NULL, but with NO database default: the Exposed column is `clientDefault`, which fills
    -- the value in Kotlin and never reaches the DDL. A `DEFAULT ''` here is real drift --
    -- SchemaFlywayParityTest caught it -- and it would also let a hand-written INSERT create a
    -- party that silently never matches the normalized-name rung of the resolution ladder.
    normalized_name          VARCHAR(255) NOT NULL,
    merged_into_partner_id   BIGINT       CONSTRAINT fk_business_partner_merged_into_partner_id__id
                                                REFERENCES business_partner (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    notes                    TEXT,
    created_at               TIMESTAMP    NOT NULL,
    updated_at               TIMESTAMP
);

-- The resolution ladder's last rung reads `business_partner.normalized_name`, which is why the
-- creditor role no longer needs an index of its own.
CREATE INDEX idx_business_partner_normalized_name ON business_partner (normalized_name);
CREATE INDEX idx_business_partner_vat_id          ON business_partner (vat_id);
CREATE INDEX idx_business_partner_shop            ON business_partner (shop_id);
CREATE INDEX idx_business_partner_merged_into     ON business_partner (merged_into_partner_id);

-- NOT NULL straight away: the tables are empty, so there is no nullable phase to walk through.
--
-- The constraints are named explicitly, and both actions are spelled out, because that is what
-- Exposed generates for `reference(..., onDelete = RESTRICT)`. Left to PostgreSQL the constraint
-- would be `supplier_partner_id_fkey` with no stated ON UPDATE, and SchemaFlywayParityTest would
-- (rightly) report the Flyway DDL and the table objects as disagreeing.
ALTER TABLE supplier
    ADD COLUMN partner_id BIGINT NOT NULL CONSTRAINT fk_supplier_partner_id__id
        REFERENCES business_partner (id) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE debtor
    ADD COLUMN partner_id BIGINT NOT NULL CONSTRAINT fk_debtor_partner_id__id
        REFERENCES business_partner (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

-- Bank accounts belong to the company, not to one direction of trade. `supplier_bank_account` is
-- empty -- the guard at the top of this file proves it -- so the column is NOT NULL straight away,
-- with no nullable phase to walk through.
--
-- Named constraint with both actions spelled out, for the same reason as the two role FKs above.
-- The CASCADE matches the role FK the creditor link used to carry -- deleting the company takes its
-- accounts with it.
ALTER TABLE supplier_bank_account
    ADD COLUMN partner_id BIGINT NOT NULL CONSTRAINT fk_supplier_bank_account_partner_id__id
        REFERENCES business_partner (id) ON DELETE CASCADE ON UPDATE RESTRICT;

CREATE INDEX idx_supplier_bank_account_partner ON supplier_bank_account (partner_id);

-- Replaces the role-scoped `(supplier_id, iban)` uniqueness V81 used to declare.
CREATE UNIQUE INDEX idx_supplier_bank_account_partner_iban
    ON supplier_bank_account (partner_id, iban);

-- ---------------------------------------------------------------------------------------------
-- One partner, one role -- on both sides.
--
-- These are UNIQUE from the outset. The unmerged chain created a plain index on each column and
-- replaced it with a unique one a migration or two later; a unique index on the same single column
-- serves every lookup the non-unique one did, so the pair only ever cost a second index for the
-- length of one upgrade.
--
-- ## Creditor side: what this replaces
--
-- NOT a unique index on `business_partner.vat_id`, and not the `supplier_vat_id_unique` V81 used to
-- carry. The party's VAT id is deliberately non-unique: a creditor and a debtor that happen to
-- share one are two parties until a human merges them (PartnerPerRoleTest asserts exactly that, and
-- a unique index there would make the merge workflow unreachable).
--
-- What actually holds -- and what the code relies on -- is one creditor role per company. The
-- creditor lookup for a party does `firstOrNull()` on this assumption, and the payment-matching
-- join `supplier INNER JOIN supplier_bank_account ON partner_id` is ambiguous without it.
--
-- The SUPPLIER_VAT_ID_TAKEN 409 the create dialog renders next to the VAT field survives in
-- `SupplierServiceImpl.requireVatIdFree`, whose meaning becomes "a creditor already exists for the
-- company with this VAT id" -- still precisely what the operator needs to be told.
--
-- ## Debtor side: why it is not merely symmetry
--
-- `DebtorTable`'s own KDoc says the debtor role is the mirror of `supplier`, and the debtor lookup
-- for a party reads it as one row the same way the creditor lookup does. Beyond that, the
-- custom-export schema graph decides whether a relation may be LEFT joined by reading the **unique
-- indexes**. A non-unique index on the child column means "many debtor rows per partner", so the
-- graph derives the relation as to-many and refuses it -- and the Debitorennummer could then never
-- be offered on a partner export. The relation would be hidden by a missing index rather than by
-- any decision anyone made.
--
-- `SupplierTable.init` and `DebtorTable.init` declare `uniqueIndex(partnerId)`. Both, or neither:
-- the Exposed declaration is what the graph reads, and the SQL is what the database enforces. One
-- without the other either changes nothing or lies about the database, and SchemaFlywayParityTest
-- reports the disagreement.
--
-- ## Why there is no duplicate-detection guard here
--
-- The unmerged debtor migration carried a DO-block that named the partners holding more than one
-- debtor role before failing. It is not carried over, and this is not an oversight: the guard at
-- the top of THIS file has already proven `supplier` and `debtor` empty, and nothing between there
-- and here inserts a row. A table proven empty cannot hold a duplicate, so the block could only
-- ever have reported nothing.
-- ---------------------------------------------------------------------------------------------
CREATE UNIQUE INDEX idx_supplier_partner_unique ON supplier (partner_id);
CREATE UNIQUE INDEX idx_debtor_partner_unique   ON debtor   (partner_id);
