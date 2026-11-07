-- Saved custom exports: a subject, an ordered set of chosen fields, and the filters to run them with.
--
-- `subject_key` and `field_key` are VARCHARs and NOT foreign keys, on purpose. The export catalogue
-- lives in Kotlin (ExportSubject/ExportField carry query sources and predicates, which are not data),
-- so there is no table for the database to reference. A stale key is therefore possible; it is
-- reported to the operator as a *broken* definition rather than run with the columns that still
-- resolve. Do not "fix" this into a constraint -- the constraint cannot exist.
--
-- `shop_info`'s primary key is `shop_id`, not `id`.
--
-- Both foreign keys are named explicitly and spell out ON DELETE *and* ON UPDATE, because that is
-- what Exposed's `reference(...)` generates and what SchemaFlywayParityTest compares against. Left to
-- PostgreSQL the constraint would be `export_definition_shop_id_fkey` with no stated ON UPDATE, and
-- the parity test would (rightly) report the DDL and the table objects as disagreeing.
CREATE TABLE export_definition (
    id                      BIGSERIAL PRIMARY KEY,
    shop_id                 BIGINT       NOT NULL CONSTRAINT fk_export_definition_shop_id__shop_id
                                             REFERENCES shop_info (shop_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    name                    VARCHAR(100) NOT NULL,
    subject_key             VARCHAR(100) NOT NULL,
    created_by_employee_id  VARCHAR(100),
    date_from               TIMESTAMP,
    date_to                 TIMESTAMP,
    enabled_toggles         TEXT,
    -- NOT NULL, but with NO database default: the Exposed column is `clientDefault`, which fills the
    -- value in Kotlin and never reaches the DDL. A `DEFAULT now()` here is real drift.
    created_at              TIMESTAMP    NOT NULL,
    updated_at              TIMESTAMP
);

CREATE INDEX idx_export_definition_shop ON export_definition (shop_id);

-- CASCADE: a field selection has no meaning without the definition that owns it, and deleting an
-- export must not leave orphan columns behind.
CREATE TABLE export_definition_field (
    id             BIGSERIAL PRIMARY KEY,
    definition_id  BIGINT       NOT NULL CONSTRAINT fk_export_definition_field_definition_id__id
                                    REFERENCES export_definition (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    field_key      VARCHAR(100) NOT NULL,
    position       INT          NOT NULL,
    heading        VARCHAR(100) NOT NULL
);

CREATE INDEX idx_export_definition_field_definition ON export_definition_field (definition_id);

-- The CUSTOM_EXPORT_READ / CUSTOM_EXPORT_MANAGE grants that used to sit at the bottom of this file
-- are declared in `V85__seed_permission_groups.sql` instead -- the single final-state permission
-- seed, and the required home for every capability grant while this chain is still unreleased.
-- Granting them here would have inserted rows a few files after V85 seeded the very table, in the
-- same upgrade run. V85's assertion block carries the check that used to live here: seven
-- custom-export grants, both tiers for MANAGER/ACCOUNTANT/ADMIN plus the read tier alone for
-- AUDITOR. See the "READ THIS BEFORE ADDING A CAPABILITY" note there before adding another.
