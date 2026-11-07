-- Squashed from V110-V115 (release v1.1.0). Final state only: V111's DELETE and the
-- successive top-ups in V112-V115 are folded in, so no grant is inserted and then removed.
-- The grant list is generated from the legacy chain, not hand-transcribed.
--
-- Numbering note: this file is the "permission group seed" group and now carries its final number,
-- V85 -- it was previously parked at V110. It creates its own tables (`permission_group`,
-- `employee_permission_group`, ...) and reads `employees`, which predates the window, so it has no
-- ordering dependency on any other group in the window. Nothing may pin the literal V110 to find
-- it: V110LegacyGrantGuardTest locates this file by the CONTENT of its legacy-grant DO-block.
--
-- Stage 2a-3 of the capability-permissions arc: capability resolution lives in the database
-- rather than in the hardcoded CapabilityBundles.
--
-- Mirrors the Exposed objects in core-data/.../features/employee/database/tables/ --
-- SchemaFlywayParityTest asserts the two agree, so a change here must be made there as well.
-- Mirrors CapabilityBundles (shared/.../core/auth/CapabilityBundles.kt); SeededGroupsMatchBundlesTest
-- holds the two together.
--
-- TIMESTAMP (not TIMESTAMPTZ) deliberately: Exposed's `datetime` is a LocalDateTime.
--
-- No COMMENT ON COLUMN: these tables are in the parity test's compared set, and a stored column
-- comment makes Exposed emit a pending `COMMENT ON COLUMN ... IS NULL`, failing parity.
--
-- NOTE the employee table is `employees` with primary key `employee_id`, not `employee`/`id`.

-- ---------------------------------------------------------------------------------------------
-- Schema
-- ---------------------------------------------------------------------------------------------

CREATE TABLE permission_group (
    id            SERIAL PRIMARY KEY,
    code          VARCHAR(50)  NOT NULL UNIQUE,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(500),
    is_system     BOOLEAN      NOT NULL,
    is_deletable  BOOLEAN      NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);

CREATE TABLE permission_group_capability (
    group_id    INT         NOT NULL CONSTRAINT fk_permission_group_capability_group_id__id
                                REFERENCES permission_group (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    capability  VARCHAR(64) NOT NULL,
    PRIMARY KEY (group_id, capability)
);

CREATE TABLE employee_permission_group (
    employee_id UUID NOT NULL PRIMARY KEY
                     CONSTRAINT fk_employee_permission_group_employee_id__employee_id
                         REFERENCES employees (employee_id) ON DELETE CASCADE ON UPDATE RESTRICT,
    group_id    INT  NOT NULL CONSTRAINT fk_employee_permission_group_group_id__id
                         REFERENCES permission_group (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

-- ---------------------------------------------------------------------------------------------
-- Seed the groups.
--
-- The first four codes are exactly the four EmployeeRole names -- the backfill below joins on
-- that, and the assertion after it fails the migration if the coupling ever breaks. The last
-- three are the purpose-built groups (Stage 3), which no employee role maps to.
--
-- is_system/is_deletable are TRUE/FALSE throughout: the Stage 5 API must refuse to edit or delete
-- any of them.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permission_group (code, name, description, is_system, is_deletable, created_at, updated_at)
VALUES
    ('TERMINAL_DEVICE', 'Terminal Device', 'Login-less stations and terminal accounts', TRUE, FALSE, NOW(), NOW()),
    ('CASHIER',         'Cashier',         'Till operations including billing',         TRUE, FALSE, NOW(), NOW()),
    ('MANAGER',         'Manager',         'Back-office operations',                    TRUE, FALSE, NOW(), NOW()),
    ('ADMIN',           'Administrator',   'Complete access',                           TRUE, FALSE, NOW(), NOW()),
    ('ACCOUNTANT', 'Accountant',
     'Books invoices, documents and the cash ledger, and runs the DATEV exports. Cannot close a period, delete a document, or change catalog, staff or system settings.',
     TRUE, FALSE, NOW(), NOW()),
    ('AUDITOR', 'Auditor',
     'Read-only access for an external audit, including the DSFinV-K export. Writes nothing at all - not even their own PIN.',
     TRUE, FALSE, NOW(), NOW()),
    ('ORDER_ONLY', 'Order Taking Only',
     'Takes and views orders at a table, and nothing else. No bills, payments, vouchers, reservations or reports.',
     TRUE, FALSE, NOW(), NOW());

-- ---------------------------------------------------------------------------------------------
-- Their capabilities: the FINAL state after V110-V115, generated from the legacy chain, plus the
-- CUSTOM_EXPORT_READ/CUSTOM_EXPORT_MANAGE grants folded in from the export-definition migration
-- that used to add them a few files later in the same upgrade run.
--
-- ## READ THIS BEFORE ADDING A CAPABILITY
--
-- A capability added to the Kotlin `Capability` enum reaches the two kinds of install by two
-- different routes:
--
--   * a **fresh** install builds its schema through Exposed and seeds `permission_group_capability`
--     from `CapabilityBundles` in SchemaManager, so it picks the new capability up automatically;
--   * an **upgraded** install is seeded only by Flyway, and this list is hardcoded -- it knows
--     nothing about anything added to the enum afterwards.
--
-- So on an upgraded install the capability simply never exists, no group holds it, the feature is
-- unreachable, and *nothing fails* to tell you. SeededGroupsMatchBundlesTest cannot catch it either:
-- it exercises the H2 seed, which SchemaManager generates *from* CapabilityBundles, so it agrees by
-- construction.
--
-- While this file is still unreleased, a new capability belongs HERE -- in the group's list, with
-- the per-group total in the assertion block updated to match, or the migration fails loudly. Once
-- this file has shipped it may never be edited again, and a new capability then needs its own
-- additive migration (`INSERT ... SELECT id, cap FROM permission_group ... ON CONFLICT DO NOTHING`,
-- with a DO-block asserting the grant landed -- selecting by a group code that does not match
-- inserts nothing, silently).
-- ---------------------------------------------------------------------------------------------

-- TERMINAL_DEVICE (27 capabilities)
INSERT INTO permission_group_capability (group_id, capability)
SELECT id, cap FROM permission_group CROSS JOIN (VALUES
    ('BILL_READ'), ('CARD_TERMINAL_READ'), ('CATALOG_READ'), ('CHANGELOG_READ'), ('CONFIG_READ'),
    ('CUSTOMER_READ'), ('CUSTOMER_WRITE'), ('NOTIFICATION_READ'), ('ORDER_READ'), ('ORDER_WRITE'),
    ('PAYROLL_READ'), ('PRINTER_READ'), ('PRINTER_WRITE'), ('PROMOTION_READ'), ('RESERVATION_READ'),
    ('RESERVATION_WRITE'), ('SELF_READ'), ('SELF_WRITE'), ('SHOP_INFO_READ'), ('SYNC_READ'),
    ('TABLE_READ'), ('TABLE_WRITE'), ('TERMINAL_READ'), ('TERMINAL_SELF_REGISTER'),
    ('VOUCHER_READ'), ('VOUCHER_WRITE'), ('ZBON_READ')
) AS v(cap) WHERE code = 'TERMINAL_DEVICE';

-- CASHIER (33 capabilities)
INSERT INTO permission_group_capability (group_id, capability)
SELECT id, cap FROM permission_group CROSS JOIN (VALUES
    ('BILL_CHANGE_PAYMENT'), ('BILL_READ'), ('BILL_WRITE'), ('CARD_TERMINAL_READ'),
    ('CATALOG_READ'), ('CHANGELOG_READ'), ('CONFIG_READ'), ('CUSTOMER_LIST'), ('CUSTOMER_READ'),
    ('CUSTOMER_WRITE'), ('DOCUMENT_READ'), ('NOTIFICATION_READ'), ('ORDER_READ'), ('ORDER_WRITE'),
    ('PAYROLL_READ'), ('PRINTER_READ'), ('PRINTER_WRITE'), ('PROMOTION_READ'), ('REPORT_READ'),
    ('RESERVATION_READ'), ('RESERVATION_WRITE'), ('SELF_READ'), ('SELF_WRITE'), ('SHOP_INFO_READ'),
    ('SYNC_READ'), ('TABLE_READ'), ('TABLE_WRITE'), ('TERMINAL_READ'), ('TERMINAL_SELF_REGISTER'),
    ('VOUCHER_REPORT'), ('VOUCHER_READ'), ('VOUCHER_WRITE'), ('ZBON_READ')
) AS v(cap) WHERE code = 'CASHIER';

-- MANAGER (84 capabilities)
INSERT INTO permission_group_capability (group_id, capability)
SELECT id, cap FROM permission_group CROSS JOIN (VALUES
    ('ACCOUNTING_READ'), ('ACCOUNTING_WRITE'), ('AUDIT_READ'), ('BILL_CHANGE_PAYMENT'),
    ('BILL_READ'), ('BILL_VOID'), ('BILL_WRITE'), ('CARD_TERMINAL_OPERATE'), ('CARD_TERMINAL_READ'),
    ('CASHBOOK_BACKDATE'), ('CASHBOOK_CLOSE'), ('CASHBOOK_READ'), ('CASHBOOK_WRITE'), ('CATALOG_MANAGE'),
    ('CATALOG_READ'),
    ('CHANGELOG_READ'), ('CONFIG_READ'), ('CONFIG_WRITE'), ('CONTRACT_READ'), ('CONTRACT_WRITE'),
    ('CREDITOR_READ'), ('CREDITOR_WRITE'), ('CUSTOM_EXPORT_MANAGE'), ('CUSTOM_EXPORT_READ'),
    ('CUSTOMER_LIST'), ('CUSTOMER_READ'), ('CUSTOMER_WRITE'),
    ('DEBTOR_READ'), ('DEBTOR_WRITE'), ('DISCOUNT_GRANT'), ('DOCUMENT_DELETE'), ('DOCUMENT_READ'),
    ('DOCUMENT_WRITE'), ('DSFINVK_EXPORT'), ('ELEVATION_APPROVE'), ('EMPLOYEE_CREDENTIAL_RESET'),
    ('EMPLOYEE_READ'), ('EMPLOYEE_WRITE'), ('INVOICE_READ'), ('INVOICE_WRITE'),
    ('KASSEN_MELDUNG_READ'), ('MONTHLY_CLOSE'), ('NOTIFICATION_READ'), ('ORDER_ITEM_CANCEL'),
    ('ORDER_READ'), ('ORDER_WRITE'), ('PAYROLL_READ'), ('PRICE_OVERRIDE'), ('PRINTER_READ'),
    ('PRINTER_WRITE'), ('PROMOTION_MANAGE'), ('PROMOTION_READ'), ('REPORT_ALL_READ'),
    ('REPORT_READ'), ('RESERVATION_DELETE'), ('RESERVATION_READ'), ('RESERVATION_WRITE'),
    ('SCHEDULED_JOB_READ'), ('SCHEDULED_JOB_WRITE'), ('SELF_READ'), ('SELF_WRITE'),
    ('SESSION_READ'), ('SESSION_WRITE'), ('SETUP_READ'), ('SHOP_INFO_READ'), ('SYNC_READ'),
    ('SYSTEM_CONFIG'), ('TABLE_READ'), ('TABLE_SESSION_OVERRIDE'), ('TABLE_WRITE'),
    ('TERMINAL_MANAGE'), ('TERMINAL_READ'), ('TERMINAL_SELF_REGISTER'),
    ('TIMETRACKING_DATEV_EXPORT'), ('TIMETRACKING_READ'), ('TIMETRACKING_WRITE'),
    ('TRAINING_REPORT_READ'), ('UPDATE_READ'), ('VOUCHER_REPORT'), ('VOUCHER_READ'),
    ('VOUCHER_WRITE'), ('ZBON_CLOSE'), ('ZBON_MANAGE'), ('ZBON_READ')
) AS v(cap) WHERE code = 'MANAGER';

-- ADMIN (100 capabilities)
INSERT INTO permission_group_capability (group_id, capability)
SELECT id, cap FROM permission_group CROSS JOIN (VALUES
    ('ACCOUNTING_READ'), ('ACCOUNTING_WRITE'), ('AUDIT_READ'), ('BACKUP_CREATE'),
    ('BACKUP_RESTORE'), ('BILL_CHANGE_PAYMENT'), ('BILL_READ'), ('BILL_VOID'), ('BILL_WRITE'),
    ('CARD_TERMINAL_MANAGE'), ('CARD_TERMINAL_OPERATE'), ('CARD_TERMINAL_READ'), ('CASHBOOK_ADMIN'),
    ('CASHBOOK_BACKDATE'), ('CASHBOOK_CLOSE'), ('CASHBOOK_READ'), ('CASHBOOK_WRITE'), ('CATALOG_MANAGE'),
    ('CATALOG_READ'),
    ('CHANGELOG_READ'), ('CONFIG_READ'), ('CONFIG_WRITE'), ('CONTRACT_READ'), ('CONTRACT_WRITE'),
    ('CREDITOR_READ'), ('CREDITOR_WRITE'), ('CUSTOM_EXPORT_MANAGE'), ('CUSTOM_EXPORT_READ'),
    ('CUSTOMER_LIST'), ('CUSTOMER_READ'), ('CUSTOMER_WRITE'),
    ('DATEV_EXPORT'), ('DEBTOR_DATEV_EXPORT'), ('DEBTOR_READ'), ('DEBTOR_WRITE'),
    ('DISCOUNT_GRANT'), ('DOCUMENT_DELETE'), ('DOCUMENT_READ'), ('DOCUMENT_WRITE'),
    ('DSFINVK_EXPORT'), ('ELEVATION_APPROVE'), ('EMPLOYEE_CREDENTIAL_RESET'), ('EMPLOYEE_READ'),
    ('EMPLOYEE_WRITE'), ('ERP_INTEGRATION_ADMIN'), ('INVOICE_READ'), ('INVOICE_WRITE'),
    ('KASSEN_MELDUNG_READ'), ('MONTHLY_CLOSE'), ('NOTIFICATION_READ'), ('ORDER_ITEM_CANCEL'),
    ('ORDER_READ'), ('ORDER_WRITE'), ('PAYROLL_READ'), ('PAYROLL_WRITE'), ('PERMISSION_ADMIN'),
    ('PRICE_OVERRIDE'), ('PRINTER_READ'), ('PRINTER_WRITE'), ('PROMOTION_MANAGE'),
    ('PROMOTION_READ'), ('REPORT_ALL_READ'), ('REPORT_READ'), ('RESERVATION_DELETE'),
    ('RESERVATION_READ'), ('RESERVATION_WRITE'), ('SCHEDULED_JOB_READ'), ('SCHEDULED_JOB_WRITE'),
    ('SELF_READ'), ('SELF_WRITE'), ('SESSION_READ'), ('SESSION_REVOKE'), ('SESSION_WRITE'),
    ('SETUP_READ'), ('SHOP_INFO_MANAGE'), ('SHOP_INFO_READ'), ('SYNC_READ'), ('SYSTEM_CONFIG'),
    ('TABLE_READ'), ('TABLE_REPAIR'), ('TABLE_SESSION_OVERRIDE'), ('TABLE_WRITE'),
    ('TERMINAL_MANAGE'), ('TERMINAL_READ'), ('TERMINAL_SELF_REGISTER'), ('TIMETRACKING_ALL_READ'),
    ('TIMETRACKING_DATEV_EXPORT'), ('TIMETRACKING_READ'), ('TIMETRACKING_WRITE'),
    ('TRAINING_REPORT_READ'), ('TSE_ADMIN'), ('TSE_QUEUE_ADMIN'), ('UPDATE_MANAGE'),
    ('UPDATE_READ'), ('VOUCHER_REPORT'), ('VOUCHER_READ'), ('VOUCHER_WRITE'), ('ZBON_CLOSE'),
    ('ZBON_MANAGE'), ('ZBON_READ')
) AS v(cap) WHERE code = 'ADMIN';

-- ACCOUNTANT (46 capabilities)
INSERT INTO permission_group_capability (group_id, capability)
SELECT id, cap FROM permission_group CROSS JOIN (VALUES
    ('ACCOUNTING_READ'), ('ACCOUNTING_WRITE'), ('AUDIT_READ'), ('BILL_READ'), ('CASHBOOK_READ'),
    ('CASHBOOK_WRITE'), ('CATALOG_READ'), ('CHANGELOG_READ'), ('CONFIG_READ'), ('CONTRACT_READ'),
    ('CONTRACT_WRITE'), ('CREDITOR_READ'), ('CREDITOR_WRITE'), ('CUSTOM_EXPORT_MANAGE'),
    ('CUSTOM_EXPORT_READ'), ('CUSTOMER_LIST'), ('CUSTOMER_READ'),
    ('DATEV_EXPORT'), ('DEBTOR_DATEV_EXPORT'), ('DEBTOR_READ'), ('DEBTOR_WRITE'), ('DOCUMENT_READ'),
    ('DOCUMENT_WRITE'), ('DSFINVK_EXPORT'), ('EMPLOYEE_READ'), ('INVOICE_READ'), ('INVOICE_WRITE'),
    ('KASSEN_MELDUNG_READ'), ('NOTIFICATION_READ'), ('ORDER_READ'), ('PAYROLL_READ'),
    ('REPORT_ALL_READ'), ('REPORT_READ'), ('SELF_READ'), ('SELF_WRITE'), ('SHOP_INFO_READ'),
    ('TABLE_READ'), ('TERMINAL_READ'), ('TIMETRACKING_ALL_READ'), ('TIMETRACKING_DATEV_EXPORT'),
    ('TIMETRACKING_READ'), ('TRAINING_REPORT_READ'), ('VOUCHER_REPORT'), ('VOUCHER_READ'),
    ('ZBON_MANAGE'), ('ZBON_READ')
) AS v(cap) WHERE code = 'ACCOUNTANT';

-- AUDITOR (33 capabilities)
INSERT INTO permission_group_capability (group_id, capability)
SELECT id, cap FROM permission_group CROSS JOIN (VALUES
    ('ACCOUNTING_READ'), ('AUDIT_READ'), ('BILL_READ'), ('CASHBOOK_READ'), ('CATALOG_READ'),
    ('CHANGELOG_READ'), ('CONFIG_READ'), ('CONTRACT_READ'), ('CREDITOR_READ'),
    -- CUSTOM_EXPORT_READ and NOT CUSTOM_EXPORT_MANAGE: an auditor runs an extract someone else
    -- prepared, she does not design one, and the auditor group's whole property is that it reaches
    -- no write. This confers no new data access either -- the runner re-checks every field against
    -- the runner's own capabilities and refuses rather than blanking a column, and she already
    -- holds CREDITOR_READ and DEBTOR_READ, which gate every field of both export subjects.
    ('CUSTOM_EXPORT_READ'), ('CUSTOMER_LIST'),
    ('CUSTOMER_READ'), ('DEBTOR_READ'), ('DOCUMENT_READ'), ('DSFINVK_EXPORT'), ('EMPLOYEE_READ'),
    ('INVOICE_READ'), ('KASSEN_MELDUNG_READ'), ('ORDER_READ'), ('PAYROLL_READ'),
    ('REPORT_ALL_READ'), ('REPORT_READ'), ('SELF_READ'), ('SHOP_INFO_READ'), ('TABLE_READ'),
    ('TERMINAL_READ'), ('TIMETRACKING_ALL_READ'), ('TIMETRACKING_READ'), ('TRAINING_REPORT_READ'),
    ('VOUCHER_REPORT'), ('VOUCHER_READ'), ('ZBON_MANAGE'), ('ZBON_READ')
) AS v(cap) WHERE code = 'AUDITOR';

-- ORDER_ONLY (18 capabilities)
INSERT INTO permission_group_capability (group_id, capability)
SELECT id, cap FROM permission_group CROSS JOIN (VALUES
    ('CATALOG_READ'), ('CHANGELOG_READ'), ('CONFIG_READ'), ('CUSTOMER_READ'), ('CUSTOMER_WRITE'),
    ('NOTIFICATION_READ'), ('ORDER_READ'), ('ORDER_WRITE'), ('PRINTER_READ'), ('PRINTER_WRITE'),
    ('PROMOTION_READ'), ('SELF_READ'), ('SELF_WRITE'), ('SHOP_INFO_READ'), ('SYNC_READ'),
    ('TABLE_READ'), ('TABLE_WRITE'), ('TERMINAL_READ')
) AS v(cap) WHERE code = 'ORDER_ONLY';
-- ---------------------------------------------------------------------------------------------
-- Assert the outcome. Absolute counts: nothing else checks the seed against PostgreSQL, and a
-- truncated generated block is exactly the failure this catches.
-- ---------------------------------------------------------------------------------------------

DO $$
DECLARE
    expected CONSTANT JSONB := '{"TERMINAL_DEVICE": 27, "CASHIER": 33, "MANAGER": 84, "ADMIN": 100, "ACCOUNTANT": 46, "AUDITOR": 33, "ORDER_ONLY": 18}';
    grp      TEXT;
    want     INT;
    got      INT;
BEGIN
    FOR grp, want IN SELECT key, value::INT FROM jsonb_each_text(expected) LOOP
        SELECT COUNT(*) INTO got
        FROM permission_group_capability c
        JOIN permission_group g ON g.id = c.group_id
        WHERE g.code = grp;

        IF got <> want THEN
            RAISE EXCEPTION 'V110: group % seeded with % capabilities, expected % — the generated seed block is incomplete', grp, got, want;
        END IF;
    END LOOP;

    -- The points the folded-in migrations asserted directly, kept because each is a decision
    -- someone could plausibly get backwards.
    IF EXISTS (
        SELECT 1 FROM permission_group_capability c
        JOIN permission_group g ON g.id = c.group_id
        WHERE g.code = 'TERMINAL_DEVICE'
          AND c.capability IN ('REPORT_READ', 'DOCUMENT_READ', 'VOUCHER_REPORT', 'CUSTOMER_LIST')
    ) THEN
        RAISE EXCEPTION 'V110: TERMINAL_DEVICE holds a capability the Stage 2b trim removes';
    END IF;

    IF EXISTS (
        SELECT 1 FROM permission_group g
        WHERE g.code IN ('AUDITOR', 'ACCOUNTANT')
          AND NOT EXISTS (
              SELECT 1 FROM permission_group_capability c
              WHERE c.group_id = g.id AND c.capability = 'ZBON_MANAGE'
          )
    ) THEN
        RAISE EXCEPTION 'V110: an audit group cannot open a Z-Bon';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM permission_group_capability c
        JOIN permission_group g ON g.id = c.group_id
        WHERE c.capability = 'ELEVATION_APPROVE'
    ) THEN
        RAISE EXCEPTION 'V110: no group can authorise a step-up';
    END IF;

    IF EXISTS (
        SELECT 1 FROM permission_group_capability c
        JOIN permission_group g ON g.id = c.group_id
        WHERE g.code = 'CASHIER' AND c.capability = 'REPORT_ALL_READ'
    ) THEN
        RAISE EXCEPTION 'V110: CASHIER must not hold REPORT_ALL_READ';
    END IF;

    -- The custom-export shape, folded in from the migration that first granted it. The per-group
    -- totals above would already catch a missing row, but not a MISPLACED one: swapping AUDITOR's
    -- read tier for the manage tier keeps every total intact and hands an auditor a write.
    --
    -- 7, not 8: three groups times both tiers, plus AUDITOR's read tier alone.
    SELECT COUNT(*) INTO got
    FROM permission_group_capability c
    JOIN permission_group g ON g.id = c.group_id
    WHERE g.code IN ('MANAGER', 'ACCOUNTANT', 'ADMIN', 'AUDITOR')
      AND c.capability IN ('CUSTOM_EXPORT_READ', 'CUSTOM_EXPORT_MANAGE');
    IF got <> 7 THEN
        RAISE EXCEPTION
            'V110: expected 7 custom-export grants — both tiers for MANAGER, ACCOUNTANT and ADMIN '
            'plus CUSTOM_EXPORT_READ for AUDITOR — but found %. The custom-export feature is '
            'unreachable or an auditor can design an export', got;
    END IF;

    IF EXISTS (
        SELECT 1 FROM permission_group_capability c
        JOIN permission_group g ON g.id = c.group_id
        WHERE g.code = 'AUDITOR' AND c.capability = 'CUSTOM_EXPORT_MANAGE'
    ) THEN
        RAISE EXCEPTION 'V110: AUDITOR must not hold CUSTOM_EXPORT_MANAGE';
    END IF;

    -- The Beleg-age override, folded in from the migration that first granted it (V120). Exactly
    -- MANAGER and ADMIN: an ACCOUNTANT keeps CASHBOOK_WRITE and books late receipts, but a booking
    -- past the age limit needs a manager's approval, which this capability is.
    SELECT COUNT(*) INTO got
    FROM permission_group_capability c
    JOIN permission_group g ON g.id = c.group_id
    WHERE c.capability = 'CASHBOOK_BACKDATE';
    IF got <> 2 OR EXISTS (
        SELECT 1 FROM permission_group_capability c
        JOIN permission_group g ON g.id = c.group_id
        WHERE c.capability = 'CASHBOOK_BACKDATE' AND g.code NOT IN ('MANAGER', 'ADMIN')
    ) THEN
        RAISE EXCEPTION 'V110: CASHBOOK_BACKDATE must be held by exactly MANAGER and ADMIN, found % grant(s)', got;
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- Backfill: derive each employee's group from the role column they already carry.
--
-- Depends on V79, which normalised the legacy 'TERMINAL' role to 'TERMINAL_DEVICE' (itself the
-- successor of V42's 'BASIC_EMPLOYEE' -> 'TERMINAL'). Without that, a legacy value would match no
-- group code and the assertion below would block the deploy — correctly, but confusingly.
-- ---------------------------------------------------------------------------------------------

INSERT INTO employee_permission_group (employee_id, group_id)
SELECT e.employee_id, g.id
FROM employees e
JOIN permission_group g ON g.code = e.role;

-- Fail rather than leave an employee with no capabilities. Fires only if a role string exists that
-- no seeded group matches, which would mean the EmployeeRole <-> group-code coupling broke.
DO $$
DECLARE orphaned INT;
BEGIN
    SELECT COUNT(*) INTO orphaned
    FROM employees e
    WHERE NOT EXISTS (
        SELECT 1 FROM employee_permission_group g WHERE g.employee_id = e.employee_id
    );
    IF orphaned > 0 THEN
        RAISE EXCEPTION 'V110: % employee(s) have a role matching no permission_group; backfill incomplete', orphaned;
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- Drop the legacy grant mechanism.
--
-- NOT dead on an upgrade: neither `employee_permissions` nor `permission` is created anywhere in
-- the migration chain — they only ever existed because Exposed's SchemaUtils.create made them at
-- boot (defect D6) — so a real V80 database still carries them (the production backup does), while
-- a database built purely from the chain does not. Hence IF EXISTS / to_regclass throughout.
--
-- This guard originally asserted employee_permissions was empty in EVERY real deployment. That was
-- wrong, and it would have blocked the upgrade of every existing shop: until the grant mechanism
-- was retired, InitialSetupServiceImpl.executeOwnerSetup created the owner with
--     permissions = PermissionEnum.entries.map { it.code }.toSet()
-- so every install provisioned before that commit carries exactly nine rows -- the owner's, one per
-- EXCEPTION_* value. A later migration cannot repair this: the RAISE below aborts the transaction
-- and halts the chain, so this file itself has to get it right.
--
-- The distinction that actually matters is whether dropping a grant could REVOKE something:
--
--   * Held by an ADMIN -- subsumed by construction. The ADMIN group seeded above is
--     Capability.entries, so no EXCEPTION_* grant can confer anything its holder does not already
--     hold. Dropping revokes nothing, and this is the owner-only shape every pre-existing install
--     has. Dropped, with a NOTICE naming the count, because a fiscal system should say what it
--     discarded rather than do it silently.
--
--   * Held by anyone else -- genuinely ambiguous. A PermissionOrRole(EXCEPTION_X, MANAGER) rule let
--     a NON-manager through on the strength of the grant alone, so dropping it can take away access
--     the holder's role never gave. ABORT there, and name the holders: a blocked deploy is strictly
--     better than silently revoking access on a fiscal system, but it has to be actionable.
--
-- Rows whose employee no longer exists are ignored by both counts -- a grant belonging to nobody
-- cannot be revoked from anybody -- and are removed with the table.
-- ---------------------------------------------------------------------------------------------

DO $$
DECLARE
    subsumed_rows INT := 0;
    blocking_rows INT := 0;
    blocking_holders TEXT;
BEGIN
    IF to_regclass('public.employee_permissions') IS NULL THEN
        RAISE NOTICE 'V110: employee_permissions absent (never created by Flyway); nothing to check';
    ELSE
        SELECT COUNT(*)
          INTO subsumed_rows
          FROM employee_permissions ep
          JOIN employees e ON e.employee_id = ep.employee_id
         WHERE e.role = 'ADMIN';

        SELECT COUNT(*), string_agg(DISTINCT e.username || ' [' || e.role || ']', ', ')
          INTO blocking_rows, blocking_holders
          FROM employee_permissions ep
          JOIN employees e ON e.employee_id = ep.employee_id
         WHERE e.role <> 'ADMIN';

        IF subsumed_rows > 0 THEN
            RAISE NOTICE 'V110: dropping % legacy grant(s) held by ADMIN employees; the ADMIN group confers every capability, so nothing is revoked', subsumed_rows;
        END IF;

        IF blocking_rows > 0 THEN
            RAISE EXCEPTION 'V110: % legacy grant(s) belong to non-admin employees (%). These may confer access their role does not. Give those employees a permission group covering it, delete the rows from employee_permissions, then re-run.', blocking_rows, blocking_holders;
        END IF;
    END IF;
END $$;

DROP TABLE IF EXISTS employee_permissions;
DROP TABLE IF EXISTS permission;
