-- Squashed from V81, V82, V83, V98 and V109 (release v1.1.0): catalog (item transfers, item
-- extras, choice groups, promotions) and staff (personnel records).
--
-- Nothing folds into a CREATE TABLE here: every table these statements touch predates the squash
-- window (V81..V117). Verified by grepping the migration set for `CREATE TABLE <name>`:
--   item_extras, item_transfer, product, product_extras, employees, employee_info -- no CREATE in
--     Flyway at all; they belong to the Exposed base schema (Flyway carries deltas from V33 only)
--   product_extras_assignment -- V39
--   promotion -- V67
--   choice_group, choice_group_option, product_choice_group -- V77
-- So this file is a straight concatenation of the five sources in version order. No statement was
-- dropped and none was rewritten.
--
-- The legacy-extras backfill from V83 is LIVE and kept verbatim: its targets (choice_group,
-- choice_group_option, product_choice_group) are created by V77, below the window, so an upgrading
-- V80 database reaches this file with real product_extras_assignment rows that still have to be
-- carried over. It is the only data statement in the five sources.
--
-- Numbering note: this file is the "catalog and staff" group and now carries its final number,
-- V84. It has no ordering dependency on any other group: none of the five sources reads or writes
-- a table created in-window (invoice, bank_statement, bank_statement_transaction,
-- payment_allocation, sales_invoice, sales_invoice_line, debtor, supplier, bank_account,
-- cash_book, contract), so it was free to take whichever slot the constrained groups did not
-- need. V98's header mentions the invoice FKs -- created by
-- V81__schema_documents_and_invoicing.sql -- only as a prose precedent for its ON UPDATE RESTRICT
-- choice. The equivalence harness compares end states, not version numbers.

-- ---------------------------------------------------------------------------------------------
-- [V81] Add index on item_transfer.transfer_time for date-range queries
-- Mirrors V77 idx_cancellation_created_at pattern
-- ---------------------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_item_transfer_transfer_time ON item_transfer (transfer_time);

-- ---------------------------------------------------------------------------------------------
-- [V82] Ensure the item_extras snapshot columns carry DB-level defaults.
--
-- Restoring a pre-feature backup (e.g. from v1.0.10) replays an item_extras CSV that only
-- has order_item_id + product_extras_id. The restore COPYs just those columns and relies on
-- the table's column DEFAULTs for the rest. The snapshot columns added in V78 were created
-- with defaults there, but a DB whose item_extras was created by Exposed (no default) is
-- missing them, so the COPY inserts NULL and violates NOT NULL. Set the defaults explicitly.
-- Idempotent: harmless on DBs already carrying these defaults from V78.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE item_extras ALTER COLUMN option_name_snapshot SET DEFAULT '';
ALTER TABLE item_extras ALTER COLUMN price_delta_snapshot SET DEFAULT 0;

-- ---------------------------------------------------------------------------------------------
-- [V83] item_extras.group_id and the legacy flat-extras carry-over.
--
-- The group's ID as well as its name. group_name_snapshot stays the authoritative label for
-- receipts and DSFinV-K; this column exists for RESOLUTION. Re-submitting a captured line
-- (re-order, split, edit-after-send) has to name a group: resolveSelections rejects a null
-- groupId and reads the authoritative price from the (group_id, option_id) placement, because
-- the same option can sit in several groups at different deltas. Nullable, because legacy flat
-- extras belong to no group. Groups are soft-deleted (is_active = false) rather than removed,
-- so this reference does not dangle in practice.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE item_extras ADD COLUMN group_id INTEGER REFERENCES choice_group(id) ON DELETE SET NULL;

CREATE INDEX idx_item_extras_group ON item_extras(group_id);

-- Carry the legacy flat extras over to choice groups.
--
-- product_extras_assignment (product <-> extra, priced at the extra's own product_extras.price)
-- is the mechanism choice groups replace. The order UI now offers options only through groups, so
-- every product whose extras arrive only via an assignment would silently lose the ability to add
-- them. This gives each such product one optional "Extras" group holding exactly those options at
-- exactly their current prices, so the cutover moves the capability instead of dropping it.
--
-- Priced from product_extras.price, which is what resolveLegacyExtras charged for these same
-- options -- so no line changes price as a result of this migration.
--
-- Only ACTIVE extras are carried: an inactive extra was already not sellable, and reviving it
-- inside a group would put it back on the order screen. A product left with no active extras
-- gets no group at all, which also keeps max_selections >= 1 for chk_choice_group_sel.
--
-- The assignment rows are deliberately left in place: the inventory screen still manages them,
-- and dropping data here would make the migration destructive and irreversible.
DO $$
DECLARE
    r RECORD;
    new_group_id INTEGER;
BEGIN
    FOR r IN
        SELECT pea.product_id,
               p.name AS product_name,
               COUNT(*) AS option_count
        FROM product_extras_assignment pea
        JOIN product_extras pe ON pe.id = pea.extras_id
        JOIN product p ON p.id = pea.product_id
        WHERE pe.is_active
        GROUP BY pea.product_id, p.name
    LOOP
        -- Named per product rather than a single shared "Extras" group: these options were
        -- product-specific assignments, and one shared group would attach every product's extras
        -- to every other product.
        INSERT INTO choice_group (name, en_name, ch_name, min_selections, max_selections, is_active, updated_at)
        VALUES (
            LEFT('Extras - ' || r.product_name, 255),
            LEFT('Extras - ' || r.product_name, 255),
            NULL,
            0,                 -- optional, matching the old checkbox list
            r.option_count,    -- any subset may be picked, as before
            true,
            NOW()
        )
        RETURNING id INTO new_group_id;

        INSERT INTO choice_group_option (group_id, option_id, price_delta, sort_position, is_default, updated_at)
        SELECT new_group_id,
               pe.id,
               pe.price,
               ROW_NUMBER() OVER (ORDER BY pe.name) - 1,
               false,          -- nothing was pre-ticked in the old list
               NOW()
        FROM product_extras_assignment pea
        JOIN product_extras pe ON pe.id = pea.extras_id
        WHERE pea.product_id = r.product_id
          AND pe.is_active;

        -- Sort after any group the product already has, so the migration never reorders an
        -- existing configurator.
        INSERT INTO product_choice_group (product_id, group_id, sort_position, updated_at)
        SELECT r.product_id,
               new_group_id,
               COALESCE(MAX(pcg.sort_position), -1) + 1,
               NOW()
        FROM product_choice_group pcg
        WHERE pcg.product_id = r.product_id;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------------------------
-- [V98] Make a personnel record deletable, and make "one record per employee" a schema rule.
--
-- The employee/personnel relationship is stored on BOTH sides: employee_info.employee_id is the
-- authoritative link every lookup uses, while employees.info_id is the traversal path behind
-- Employee.employeeInfo (DSFinV-K BEDIENER_NAME, EmployeeMapper's lastName fallback,
-- TemporarySessionServiceImpl). Creation writes both sides; deletion tore down only one.
--
-- Because employees.info_id was created by Exposed with the default RESTRICT, Postgres refused
-- every delete:
--
--   ERROR: update or delete on table "employee_info" violates foreign key constraint
--   "fk_employees_info_id__employee_info_id" on table "employees"
--
-- The ExposedSQLException was not translated, so DELETE /api/employee/info/by-employee/{id} always
-- answered 500 and removed nothing -- the endpoint was unreachable in practice. Restoring a backup
-- that carries no employee_info rows is a supported state, so the application could *observe* an
-- employee without a personnel record but never *produce* one.
--
-- SET NULL rather than CASCADE: deleting a personnel record must not delete the login account. It
-- clears the back-reference and leaves the employee able to sign in, which is what the route KDoc
-- promises ("the login account itself is untouched"). ON UPDATE RESTRICT matches the convention
-- used by the invoice/bank_statement FKs in the documents-and-invoicing group.
--
-- The constraint is dropped by looked-up name rather than hardcoded: the base schema is built by
-- Exposed's SchemaUtils (Flyway only carries deltas from V33), so the generated name is not
-- guaranteed identical across databases that were created by different versions.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    fk_name TEXT;
BEGIN
    SELECT con.conname INTO fk_name
    FROM pg_constraint con
    JOIN pg_attribute att
      ON att.attrelid = con.conrelid
     AND att.attnum = con.conkey[1]
    WHERE con.conrelid = 'employees'::regclass
      AND con.contype = 'f'
      AND array_length(con.conkey, 1) = 1
      AND att.attname = 'info_id';

    IF fk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE employees DROP CONSTRAINT %I', fk_name);
    END IF;
END $$;

ALTER TABLE employees
    ADD CONSTRAINT fk_employees_info_id__employee_info_id
    FOREIGN KEY (info_id) REFERENCES employee_info (employee_info_id)
    ON DELETE SET NULL ON UPDATE RESTRICT;

-- One personnel record per employee. createEmployeeInfo already rejects a second one with 409 and
-- both delete paths read the record with singleOrNull() (which throws on an ambiguous row), so this
-- only moves an existing application invariant into the schema.
--
-- Deliberately NOT defensive about pre-existing duplicates: a duplicate means two personnel records
-- disagree about one person's name, which feeds BEDIENER_NAME on fiscal receipts. Failing the
-- migration surfaces that for a human to resolve rather than silently picking a winner.
CREATE UNIQUE INDEX IF NOT EXISTS employee_info_employee_id_unique
    ON employee_info (employee_id);

-- ---------------------------------------------------------------------------------------------
-- [V109] Per-promotion "Nicht mit anderen Aktionen kombinierbar" flag.
--
-- TRUE by default so every existing promotion keeps today's stacking behaviour (ITEM_SPECIFIC
-- promotions applied first, BILL_WIDE computed on the remaining total). Distinct from
-- is_multi_use, which controls whether the SAME promotion may apply several times on one bill.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE promotion ADD COLUMN combinable BOOLEAN NOT NULL DEFAULT TRUE;
