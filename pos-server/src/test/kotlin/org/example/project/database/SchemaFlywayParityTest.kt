package org.example.project.database

import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import org.example.project.features.bank.database.tables.BankAccountTable
import org.example.project.features.bill.database.tables.BillNumberSequenceTable
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.contract.database.tables.ContractInstalmentTable
import org.example.project.features.contract.database.tables.ContractTable
import org.example.project.features.customexport.database.tables.ExportDefinitionFieldTable
import org.example.project.features.customexport.database.tables.ExportDefinitionTable
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.database.tables.BankStatementTransactionTable
import org.example.project.features.document.database.tables.InvoiceAllowanceChargeTable
import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.example.project.features.document.database.tables.InvoiceLineTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.document.database.tables.InvoiceVatBreakdownTable
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.issuereport.database.tables.IssueReportSettingsTable
import org.example.project.features.order.database.tables.OrderNumberSequenceTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.features.salesinvoice.database.tables.ArNumberSequenceTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceAllowanceChargeTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceBookingTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceLineTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceVatBreakdownTable
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.example.project.features.table.database.tables.LocationTypeTable
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.terminal.database.tables.TerminalPairingCodeTable
import org.example.project.features.tse.database.tables.TseExplanationTable
import org.example.project.features.tse.database.tables.TseExportArchiveTable
import org.example.project.features.tse.database.tables.TseLateSigningTable
import org.example.project.features.update.database.tables.UpdateSettingsTable
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.testutils.PostgresTestDatabase
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.output.MigrateResult
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.Statement
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flyway builds the production (PostgreSQL) schema; Exposed's `SchemaUtils.create` builds the H2
 * test schema — the default suite runs `initDB(runMigrations = false)`. Nothing else checks the two
 * agree, so a column added to one and not the other is invisible until production behaves
 * differently from the tests.
 *
 * This test closes that gap for **every migration on disk since the last shipped release**: it
 * starts zonky embedded PostgreSQL, hand-seeds only the preconditions those migrations need
 * (`documents`, the SET NULL FK target, and the production tables listed on
 * [seedInvoiceModelPreconditions] — the full chain's preconditions live in [MigrationChainTest]),
 * baselines Flyway at the last release marker so **the whole window genuinely executes**, then asks
 * Exposed what it would still need to change. Anything non-empty is drift.
 *
 * **Both bounds are computed from the migrations on disk**, never hard-coded — see
 * [resolveBaselineVersion] and [maxVersion]. The baseline used to be the literal `85`, chosen when
 * V86's e-invoice model was the migration of interest; the v1.1.0 squash then parked its catalog
 * group at V81, *below* that baseline, so `promotion.combinable` was never created and the parity
 * check reported drift that did not exist. Anchoring the baseline to the last release instead makes
 * the window cover everything unreleased, whatever numbers the groups currently carry.
 *
 * `addMissingColumnsStatements` catches missing and mismatched *columns* only, so the index
 * declarations are compared separately against `pg_index` — one way only, Exposed → PostgreSQL.
 * The full two-way index comparison (name, uniqueness, key, partial predicate) of a fresh install
 * against a real V76→Vmax upgrade is [FreshInstallIndexParityTest].
 *
 * ## What baselining below the window costs
 *
 * This proves the window's DDL produces a schema Exposed agrees with. It does **not** prove it
 * applies cleanly on top of the real V33..V80 chain: baselining skips those migrations, so a name
 * collision with an object an earlier migration created would be invisible here. [MigrationChainTest]
 * is the test that covers that.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Flyway DDL since the last release vs the Exposed table objects")
class SchemaFlywayParityTest {
    private val migrationLocation = "classpath:db/migration"

    private lateinit var pg: PostgresTestDatabase
    private lateinit var migrate: MigrateResult

    /**
     * Every migration version on the classpath above [baselineVersion], in ascending order —
     * computed, never hard-coded, in the spirit of [MigrationChainTest].
     *
     * Both the Flyway target and the "genuinely executed" floor come from this list, because both
     * rot the instant they are written down as literals. The target was once pinned at `"113"`, and
     * V117 (`contract.due_weekday`) then made Flyway and Exposed legitimately disagree — the Exposed
     * object had the column, the truncated run did not. The floor was then an exact list ending at
     * V113, and the V81–V117 squash deleted fourteen of the versions it named. Same defect, twice.
     *
     * Derived from `Flyway.info()`, so the V106–V108 gap needs no special case: those numbers are
     * reserved by an unmerged branch, are legitimately absent, and simply never appear in the list.
     */
    private var versionsAboveBaseline: List<String> = emptyList()

    /**
     * The version Flyway is baselined at — **derived**, never written down. See
     * [resolveBaselineVersion]: it is the last release marker on disk below the first squashed
     * schema group, i.e. "the last shipped release before everything this window contains".
     */
    private lateinit var baselineVersion: String

    /** The top of [versionsAboveBaseline] — what Flyway is targeted at. */
    private val maxVersion: String
        get() = versionsAboveBaseline.last()

    private val tables: Array<Table>
        get() =
            arrayOf(
                InvoiceTable,
                BusinessPartnerTable,
                SupplierTable,
                SupplierBankAccountTable,
                InvoiceLineTable,
                InvoiceVatBreakdownTable,
                InvoiceAllowanceChargeTable,
                InvoiceBookingTable,
                BankAccountTable,
                BankStatementTable,
                BankStatementTransactionTable,
                PaymentAllocationTable,
                ContractTable,
                ContractInstalmentTable,
                DebtorTable,
                SalesInvoiceTable,
                SalesInvoiceLineTable,
                SalesInvoiceVatBreakdownTable,
                SalesInvoiceAllowanceChargeTable,
                SalesInvoiceBookingTable,
                ArNumberSequenceTable,
                PromotionTable,
                PermissionGroupTable,
                PermissionGroupCapabilityTable,
                EmployeePermissionGroupTable,
                ExportDefinitionTable,
                ExportDefinitionFieldTable,
                // On a fresh install Exposed creates `update_settings`; on an upgrade
                // `V87__schema_app_update.sql` does. Nothing else compares the two, so a divergence
                // would make fresh and upgraded installs differ silently.
                UpdateSettingsTable,
                IssueReportSettingsTable,
                // V82: the permanent Nachsignierung record. Exposed creates it on a fresh install,
                // the migration on an upgrade.
                TseLateSigningTable,
                // V82: append-only TANR-without-POS-record explanations (design 2026-09-22 D7).
                TseExplanationTable,
                // V82: the TSE archive register (real-TSE QA rt14).
                TseExportArchiveTable,
                // V77 (Zahlartwechsel storno_reason/rebooks_bill_id) + zbon rebook_storno_* columns.
                BillTable,
                // V82: the Kassenbeleg month counter (real-TSE QA rt12, duplicate bill numbers).
                BillNumberSequenceTable,
                // V82: the order-number counter (duplicate ORD-… under parallel POST /api/orders).
                OrderNumberSequenceTable,
                ZBonTable,
                // F51 employee-tip payouts (folded into V82); FKs onto the zbon/employees/
                // kassenbuch_header stubs seeded below.
                EmployeeTipPayoutTable,
                // V79 (folded, F42): the pairing code's register-at-the-TSE choice.
                TerminalPairingCodeTable,
                // V131: the `is_system` flag on the virtual location types and the VOUCHER table.
                LocationTypeTable,
                RTableTable,
            )

    @BeforeAll
    fun setUpAndMigrate() {
        pg = PostgresTestDatabase.start()
        pg.dataSource.connection.use { seedInvoiceModelPreconditions(it) }
        baselineVersion = resolveBaselineVersion()
        versionsAboveBaseline = migrationVersionsAboveBaseline()

        migrate =
            Flyway
                .configure()
                .dataSource(pg.dataSource)
                .locations(migrationLocation)
                .baselineOnMigrate(true)
                .baselineVersion(baselineVersion)
                .baselineDescription("last released schema before the window (hand-seeded)")
                .target(maxVersion)
                .validateMigrationNaming(true)
                .load()
                .migrate()

        Database.connect(pg.dataSource)
    }

    @AfterAll
    fun tearDown() {
        if (::pg.isInitialized) pg.close()
    }

    @Test
    @DisplayName("Every migration since the last release genuinely executes (a skipped one makes everything below vacuous)")
    fun migrationsGenuinelyExecute() {
        assertTrue(migrate.success, "Flyway migrate() must succeed")
        // WHY THIS GUARD EXISTS: baselining below the window means Flyway will happily mark a migration as
        // below-baseline and skip it, and a skipped migration makes the parity check below vacuous —
        // Exposed finds nothing to add because nothing was ever asked to run. That is not
        // hypothetical: while the target was pinned at 101, V102 went unexecuted here for real, and
        // because its two tables sit outside the compared set nothing else noticed.
        //
        // The floor is DERIVED, not written down: every migration on disk above the baseline must
        // appear in `executed`. That is strictly stronger than the literal list this replaces — it
        // covers a new migration the day it lands, which a literal list never does — and it cannot
        // rot. The literal version of this floor named 25 versions; the V81–V117 squash deleted 14
        // of them and the assertion failed on the bookkeeping rather than on any defect, which is
        // precisely the failure mode the comment on the old hard-coded target had already predicted.
        val executed = migrate.migrations.mapNotNull { it.version }.toSet()
        val skipped = versionsAboveBaseline.filterNot { it in executed }
        assertTrue(
            skipped.isEmpty(),
            "Every migration above the V$baselineVersion baseline must have EXECUTED, not been " +
                "skipped; skipped=$skipped, executed=$executed",
        )
        // ...and Flyway must agree it reached the top of the chain.
        assertEquals(
            maxVersion,
            migrate.targetSchemaVersion,
            "Flyway's applied target must equal the highest migration on disk",
        )
        assertTrue(
            maxVersion in executed,
            "The highest on-disk migration V$maxVersion must itself have executed; executed=$executed",
        )
        for (table in tables) {
            assertTrue(tableExists(table.tableName), "the migrations must create table ${table.tableName}")
        }
    }

    @Test
    @DisplayName("Exposed has nothing left to add or alter after Flyway has run")
    fun exposedHasNothingToAddAfterFlywayHasRun() {
        val statements = transaction { SchemaUtils.addMissingColumnsStatements(tables = tables) }
        assertTrue(
            statements.isEmpty(),
            "Flyway (V$baselineVersion..V$maxVersion) and the Exposed tables disagree. Exposed would still run:\n" +
                statements.joinToString("\n"),
        )
    }

    @Test
    @DisplayName("Every index the Exposed objects declare exists in the Flyway DDL, with the same uniqueness")
    fun everyExposedIndexExistsInPostgres() {
        val missing = mutableListOf<String>()
        for (table in tables) {
            val actual = postgresIndexes(table.tableName)
            for (index in table.indices) {
                val expected = index.columns.joinToString(",") { it.name } to index.unique
                if (expected !in actual) {
                    missing += "${table.tableName}: ${expected.first} (unique=${expected.second}); DB has $actual"
                }
            }
        }
        assertTrue(
            missing.isEmpty(),
            "Indexes declared in Exposed but absent (or with different uniqueness) in the Flyway DDL:\n" +
                missing.joinToString("\n"),
        )
    }

    /**
     * The objects the migrations under test need that no earlier migration creates in this test's
     * hand-built base. The seed stays otherwise minimal — the full pre-migration base lives in
     * [MigrationChainTest].
     *
     * `documents` is the target of the two `ON DELETE SET NULL` FKs. Only `id` matters for those,
     * but V89 indexes `(processing_status, uploaded_at)`, so both are seeded in the types
     * `DocumentTable` declares — `varchar(20)` defaulting to `NONE`, and a `datetime`, which Exposed
     * maps to `TIMESTAMP WITHOUT TIME ZONE`.
     *
     * V92's `bank_account` adds a second precondition, `shop_info`, whose primary key is `shop_id`.
     * V94's `payment_allocation` adds a third, `kassenbuch_header`: it exists in production long
     * before V86, so no migration in the range creates it, but two of V94's FKs point at its `id`.
     * V95's `contract` adds no fourth — it references `shop_info`, `supplier` (V90) and `documents`,
     * all already here, and `contract_instalment` references `contract` and `invoice`. V96 only
     * ALTERs `supplier` and `contract`, V97 only ALTERs `bank_statement_transaction` and
     * `payment_allocation`, and V91 only ALTERs `bank_statement_transaction`, so none adds a fourth.
     */
    private fun seedInvoiceModelPreconditions(conn: Connection) {
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE documents (
                    id                BIGSERIAL PRIMARY KEY,
                    processing_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
                    uploaded_at       TIMESTAMP   NOT NULL
                )
                """.trimIndent(),
            )
            st.execute("CREATE TABLE shop_info (shop_id BIGSERIAL PRIMARY KEY)")
            // `shop_id` is needed by the cash-and-fiscal group's backfill
            // (`UPDATE kassenbuch_header h SET cash_book_id = cb.id ... WHERE cb.shop_id = h.shop_id`),
            // which only started executing here when the baseline moved down to the last release.
            st.execute("CREATE TABLE kassenbuch_header (id BIGSERIAL PRIMARY KEY, shop_id BIGINT)")
            // V98 re-points employees.info_id at employee_info with ON DELETE SET NULL and makes
            // employee_id unique there; V110 backfills employee_permission_group by joining on
            // `role`. The real table is created below the baseline, which baselining skips, so this stub must
            // carry every column the migrations UNDER TEST actually touch — `role` was added when
            // V110 failed here with "column e.role does not exist".
            st.execute("CREATE TABLE employees (employee_id UUID PRIMARY KEY, info_id INTEGER, role VARCHAR(50))")
            st.execute(
                """
                CREATE TABLE employee_info (
                    employee_info_id SERIAL PRIMARY KEY,
                    employee_id      UUID NOT NULL REFERENCES employees(employee_id)
                )
                """.trimIndent(),
            )
            // V99's debtor.customer_id, sales_invoice.bill_id and
            // sales_invoice_line.source_order_item_id point at tables that exist in production long
            // before V86, so no migration in this range creates them. Only the keys the FKs name are
            // needed. `t_order_item` is the real table name behind OrderItemTable.
            st.execute("CREATE TABLE customers (customer_id UUID PRIMARY KEY)")
            // FK targets `bill` needs that no earlier stub creates — production tables from long
            // before V86, needed here only because `bill` is now fully compared (below).
            st.execute("CREATE TABLE table_session (table_session_id BIGSERIAL PRIMARY KEY)")
            // V131 adds `is_system` to both. They are production tables from long before the baseline
            // (V54 created `location_types`), so the stubs carry every column the real schema has
            // BELOW V131 and the compared set can prove the migration made the column match Exposed.
            st.execute(
                """
                CREATE TABLE location_types (
                    id            BIGSERIAL PRIMARY KEY,
                    name          VARCHAR(100) NOT NULL,
                    english_name  VARCHAR(100) NOT NULL,
                    chinese_name  VARCHAR(100) NOT NULL,
                    is_virtual    BOOLEAN NOT NULL DEFAULT FALSE,
                    is_takeaway   BOOLEAN NOT NULL DEFAULT FALSE,
                    sort_order    INTEGER NOT NULL DEFAULT 0,
                    created_at    TIMESTAMP NOT NULL,
                    updated_at    TIMESTAMP NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE r_table (
                    table_id                  BIGSERIAL PRIMARY KEY,
                    table_number              VARCHAR(10) NOT NULL UNIQUE,
                    seats                     INTEGER,
                    location_type_id          BIGINT NOT NULL
                        CONSTRAINT fk_r_table_location_type_id__id
                        REFERENCES location_types (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    table_status              VARCHAR(50) NOT NULL DEFAULT 'FREE',
                    created_at                TIMESTAMP NOT NULL,
                    current_table_session_id  BIGINT
                        CONSTRAINT fk_r_table_current_table_session_id__table_session_id
                        REFERENCES table_session (table_session_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    position_x                REAL,
                    position_y                REAL,
                    rotation                  REAL,
                    width                     REAL,
                    height                    REAL,
                    is_active                 BOOLEAN NOT NULL DEFAULT TRUE,
                    assignment_priority       INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            st.execute("CREATE TABLE terminals (id BIGSERIAL PRIMARY KEY)")
            // `terminal_pairing_codes` is created by V79 — below the baseline, so baselined away here
            // — and is seeded with V79's columns, including `register_tse_client` (F42, folded into
            // V79 before v1.1.0). It is compared below so the seed and TerminalPairingCodeTable
            // cannot drift apart silently; MigrationChainTest runs V79 itself. The foreign key carries
            // Exposed's name rather than the `terminal_pairing_codes_terminal_id_fkey` PostgreSQL
            // gave V79's unnamed REFERENCES: that naming difference predates this window (it is a
            // released migration's) and is not what this comparison is here to catch.
            st.execute(
                """
                CREATE TABLE terminal_pairing_codes (
                    id                  BIGSERIAL PRIMARY KEY,
                    terminal_id         BIGINT NOT NULL CONSTRAINT fk_terminal_pairing_codes_terminal_id__id
                                            REFERENCES terminals(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    code                VARCHAR(12) NOT NULL,
                    expires_at          TIMESTAMP NOT NULL,
                    consumed_at         TIMESTAMP,
                    admin_session_epoch BIGINT,
                    register_tse_client BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at          TIMESTAMP NOT NULL
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX idx_terminal_pairing_codes_code ON terminal_pairing_codes(code)")
            st.execute(
                "CREATE TABLE tse_transaction (id BIGSERIAL PRIMARY KEY, signature VARCHAR(1000), " +
                    "tse_serial_number VARCHAR(100), transaction_number BIGINT, " +
                    "reference_id VARCHAR(100), entity_type VARCHAR(30))",
            )
            // `bill` and `zbon` (below) are production tables from long before V86 and are now
            // fully in the compared [tables] set (Task 6, 2026-09-29), so — unlike the id-only
            // stubs elsewhere in this seed — they must carry every column AND foreign key the real
            // schema has at the V80 baseline. That includes `storno_reason`, `rebooks_bill_id` and
            // `rebook_storno_count`/`rebook_storno_amount`: those columns are added by V77, but V77
            // sits BELOW this test's V80 baseline and is baselined away — it never runs here, so
            // nothing in this range would otherwise create them. (MigrationChainTest is what
            // actually exercises V77; it baselines at V77 itself and runs V78+.) V81 below adds
            // `clears_sales_invoice_id` itself, so it is deliberately absent here. `zbon_id` is
            // added via ALTER after `zbon` itself exists (below).
            st.execute(
                """
                CREATE TABLE bill (
                    id                BIGSERIAL PRIMARY KEY,
                    employee_id       UUID NOT NULL
                        CONSTRAINT fk_bill_employee_id__employee_id
                        REFERENCES employees (employee_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    tip_recipient     VARCHAR(50) NOT NULL DEFAULT 'EMPLOYEE',
                    table_session     BIGINT NOT NULL
                        CONSTRAINT fk_bill_table_session__table_session_id
                        REFERENCES table_session (table_session_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    bill_number       VARCHAR(100) NOT NULL UNIQUE,
                    bill_amount       DECIMAL(19, 4) NOT NULL,
                    discount_amount   DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    promotion_amount  DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    paid_amount       DECIMAL(19, 4) NOT NULL,
                    tips              DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    start_time        TIMESTAMP NOT NULL,
                    end_time          TIMESTAMP NOT NULL,
                    deleting_reason   VARCHAR(10000),
                    tse_transaction_id BIGINT
                        CONSTRAINT fk_bill_tse_transaction_id__id
                        REFERENCES tse_transaction (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    deleted_bill_id   BIGINT
                        CONSTRAINT fk_bill_deleted_bill_id__id
                        REFERENCES bill (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    storno_reason     VARCHAR(32),
                    rebooks_bill_id   BIGINT
                        CONSTRAINT fk_bill_rebooks_bill_id__id
                        REFERENCES bill (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    zbon_id           BIGINT,
                    tse_signing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    is_training       BOOLEAN NOT NULL DEFAULT FALSE,
                    terminal_id       BIGINT
                        CONSTRAINT fk_bill_terminal_id__id
                        REFERENCES terminals (id) ON DELETE RESTRICT ON UPDATE RESTRICT
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX idx_bill_end_time ON bill (end_time)")
            st.execute("CREATE INDEX idx_bill_employee_end_time ON bill (employee_id, end_time)")
            st.execute("CREATE INDEX idx_bill_table_session ON bill (table_session)")
            st.execute("CREATE INDEX idx_bill_zbon_id ON bill (zbon_id)")
            st.execute("CREATE INDEX idx_bill_deleted_bill_id ON bill (deleted_bill_id)")
            st.execute("CREATE INDEX idx_bill_rebooks_bill_id ON bill (rebooks_bill_id)")
            st.execute("CREATE TABLE t_order_item (id BIGSERIAL PRIMARY KEY)")
            // V81's sales_invoice_allowance_charge.bill_promotion_id points at V67's bill_promotion,
            // which sits below this test's baseline; only the key the FK names is needed.
            st.execute("CREATE TABLE bill_promotion (id BIGSERIAL PRIMARY KEY)")
            // V102 adds creates_receivable to both of these, and V103 alters `bill` above. They are
            // production tables from long before V86, so nothing in this range creates them — the
            // same reason the stubs above exist. Until the target reached 103, V102 was never
            // executed here at all and the omission was invisible.
            st.execute("CREATE TABLE payment_type (id BIGSERIAL PRIMARY KEY)")
            st.execute("CREATE TABLE payment (id BIGSERIAL PRIMARY KEY)")
            // V82 adds `trace_id` to `transaction_audit_log`, a production table from long before
            // V86, so nothing in this range creates it. An id-only stub suffices: V82 only ADDs a
            // column and indexes it, and the table is not in the compared set above.
            st.execute("CREATE TABLE transaction_audit_log (id BIGSERIAL PRIMARY KEY)")
            // V82 creates `tse_late_signing` with an FK to `tse_transaction` and backfills it from
            // `tse_signing_queue` — both production tables from long before V86. The queue stub
            // carries exactly the columns the backfill reads; neither is in the compared set.
            // V82 adds `state` (UPDATE reads `signature`); the serial/TANR columns are read by a
            // later migration, so they are seeded here too rather than as separate follow-up churn.
            // (`tse_transaction` itself is created earlier, above, as a `bill` FK target.)
            st.execute(
                "CREATE TABLE tse_signing_queue (id BIGSERIAL PRIMARY KEY, entity_type VARCHAR(20) NOT NULL, " +
                    "reference_id VARCHAR(100) NOT NULL, signing_status VARCHAR(20) NOT NULL, " +
                    "created_at TIMESTAMP NOT NULL, last_attempt_at TIMESTAMP, signed_at TIMESTAMP, " +
                    "last_error TEXT, tse_transaction_id BIGINT)",
            )
            // V104 alters `zbon` (total_receivable_cleared) and widens
            // `kassenbuch_line_item.kind`. Both are production tables from before V86. `zbon` is
            // now fully in the compared [tables] set (Task 6, 2026-09-29), so it carries every
            // column the real schema has at the V80 baseline — including `rebook_storno_count`/
            // `rebook_storno_amount`, which V77 adds; V77 sits BELOW this test's V80 baseline and
            // is baselined away, so it never runs here and nothing else in this range would
            // otherwise create those two columns. (MigrationChainTest is what actually exercises
            // V77.) V104 below adds `total_receivable_cleared` itself, so it is deliberately
            // absent here.
            st.execute(
                """
                CREATE TABLE zbon (
                    zbon_id                 BIGSERIAL PRIMARY KEY,
                    shop_info_id            BIGINT NOT NULL
                        CONSTRAINT fk_zbon_shop_info_id__shop_id
                        REFERENCES shop_info (shop_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    employee_id             UUID NOT NULL
                        CONSTRAINT fk_zbon_employee_id__employee_id
                        REFERENCES employees (employee_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    start_time              TIMESTAMP NOT NULL,
                    end_time                TIMESTAMP NOT NULL,
                    cancelled_amount        DECIMAL(19, 4) NOT NULL,
                    total_discounts         DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    total_promotions        DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    total_pos_revenue       DECIMAL(19, 4) NOT NULL,
                    admin_tip_revenue       DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    opening_balance         DECIMAL(19, 4) NOT NULL,
                    expenses                DECIMAL(19, 4) NOT NULL,
                    cash_revenue            DECIMAL(19, 4) NOT NULL,
                    closing_balance         DECIMAL(19, 4) NOT NULL,
                    created_at              TIMESTAMP NOT NULL,
                    training_revenue        DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    training_bill_count     INT NOT NULL DEFAULT 0,
                    refund_amount           DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    storno_bill_count       INT NOT NULL DEFAULT 0,
                    rebook_storno_count     INT NOT NULL DEFAULT 0,
                    rebook_storno_amount    DECIMAL(19, 4) NOT NULL DEFAULT 0,
                    zbon_nummer             BIGINT NOT NULL DEFAULT 0,
                    stamm_name              VARCHAR(255),
                    stamm_strasse           VARCHAR(500),
                    stamm_plz               VARCHAR(10),
                    stamm_ort               VARCHAR(100),
                    stamm_land              VARCHAR(100),
                    stamm_stnr              VARCHAR(50),
                    stamm_ustid             VARCHAR(15),
                    stamm_kasse_snapshot_at TIMESTAMP,
                    stamm_kasse_brand       VARCHAR(50),
                    stamm_kasse_modell      VARCHAR(50),
                    stamm_kasse_sw_brand    VARCHAR(50),
                    stamm_kasse_sw_version  VARCHAR(50),
                    CONSTRAINT uq_zbon_shop_nummer UNIQUE (shop_info_id, zbon_nummer)
                )
                """.trimIndent(),
            )
            st.execute(
                "ALTER TABLE bill ADD CONSTRAINT fk_bill_zbon_id__zbon_id " +
                    "FOREIGN KEY (zbon_id) REFERENCES zbon (zbon_id) ON DELETE RESTRICT ON UPDATE RESTRICT",
            )
            st.execute(
                "CREATE TABLE kassenbuch_line_item (id BIGSERIAL PRIMARY KEY, " +
                    "kind VARCHAR(20) NOT NULL DEFAULT 'STANDARD')",
            )
            // V109 alters `promotion`, another pre-V86 production table. It is seeded in full (its
            // pre-V109 shape) rather than as a bare id stub, because it IS in the compared set: the
            // whole point is that Exposed finds nothing left to add once V109 has run, which only
            // means something if every other column is already there. `product` is the FK target of
            // target_product_id and is not itself compared, so an id-only stub suffices.
            //
            // PRE-EXISTING DRIFT, deliberately not reproduced here: V67 declares both FKs with
            // inline `REFERENCES`, so PostgreSQL names them `promotion_target_product_id_fkey` /
            // `promotion_created_by_fkey`, while Exposed expects
            // `fk_promotion_target_product_id__id` / `fk_promotion_created_by__employee_id` with
            // explicit RESTRICT actions. That is a naming difference in a migration that predates
            // this test by 40-odd versions and is cosmetic (same columns, same targets, same
            // default RESTRICT semantics); reproducing it would make this test fail on V67 rather
            // than on the column V109 adds. The constraints are therefore named as Exposed names
            // them. Renaming them for real belongs in its own migration, not here.
            // V116 deletes the `erp_integration` row from `shop_configuration` (and drops
            // `kassenbuch_transit_outbox`, which `DROP TABLE IF EXISTS` tolerates being absent).
            // `shop_configuration` is a pre-V86 production table, so nothing in this range creates
            // it — the same reason as the stubs above. `key`/`value` are the columns V116 names.
            st.execute(
                "CREATE TABLE shop_configuration (id BIGSERIAL PRIMARY KEY, " +
                    "key VARCHAR(100) NOT NULL, value JSONB)",
            )
            // `name` is read by the catalog group's legacy-extras carry-over
            // (`'Extras - ' || r.product_name`).
            st.execute("CREATE TABLE product (id BIGSERIAL PRIMARY KEY, name VARCHAR(255) NOT NULL DEFAULT '')")
            seedCatalogGroupPreconditions(st)
            st.execute(
                """
                CREATE TABLE promotion (
                    id                BIGSERIAL PRIMARY KEY,
                    name              VARCHAR(200)  NOT NULL,
                    description       VARCHAR(1000) NULL,
                    discount_type     VARCHAR(20)   NOT NULL,
                    discount_value    DECIMAL(19,4) NOT NULL,
                    scope             VARCHAR(20)   NOT NULL,
                    target_product_id BIGINT        NULL,
                    is_multi_use      BOOLEAN       NOT NULL DEFAULT FALSE,
                    valid_from        TIMESTAMP     NOT NULL,
                    valid_until       TIMESTAMP     NULL,
                    is_active         BOOLEAN       NOT NULL DEFAULT TRUE,
                    created_by        UUID          NOT NULL,
                    created_at        TIMESTAMP     NOT NULL,
                    CONSTRAINT fk_promotion_target_product_id__id
                        FOREIGN KEY (target_product_id) REFERENCES product(id)
                        ON DELETE RESTRICT ON UPDATE RESTRICT,
                    CONSTRAINT fk_promotion_created_by__employee_id
                        FOREIGN KEY (created_by) REFERENCES employees(employee_id)
                        ON DELETE RESTRICT ON UPDATE RESTRICT
                )
                """.trimIndent(),
            )
            // The release-marker migration at the top of the window INSERTs its notes row into
            // `changelog_release`, which V79 creates — below the baseline, so baselining skips it.
            // Same reason as every stub above. This one is different in kind, though: it is needed
            // by *whatever release marker currently tops the chain*, so it must stay seeded for
            // every future release, not just this one. Shaped exactly as V79 declares it, because
            // an INSERT that names a column this stub lacks must fail here rather than in
            // production. `changelog_release` is not in the compared set.
            st.execute(
                """
                CREATE TABLE changelog_release (
                    release_version TEXT      NOT NULL PRIMARY KEY,
                    release_date    TIMESTAMP NOT NULL DEFAULT NOW(),
                    title_en        TEXT      NOT NULL,
                    title_de        TEXT      NOT NULL,
                    title_zh        TEXT      NOT NULL,
                    summary_en      TEXT      NOT NULL,
                    summary_de      TEXT      NOT NULL,
                    summary_zh      TEXT      NOT NULL,
                    highlights_en   JSONB     NOT NULL DEFAULT '[]'::jsonb,
                    highlights_de   JSONB     NOT NULL DEFAULT '[]'::jsonb,
                    highlights_zh   JSONB     NOT NULL DEFAULT '[]'::jsonb,
                    known_issues_en JSONB     NOT NULL DEFAULT '[]'::jsonb,
                    known_issues_de JSONB     NOT NULL DEFAULT '[]'::jsonb,
                    known_issues_zh JSONB     NOT NULL DEFAULT '[]'::jsonb,
                    applied_at      TIMESTAMP NOT NULL DEFAULT NOW(),
                    applied_by      TEXT      NOT NULL DEFAULT current_user,
                    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * Preconditions of the **catalog-and-staff** squash group, which only began executing here when
     * the baseline moved down from the literal V85 to the last release marker.
     *
     * Every table below is created by Exposed's base schema or by a migration below the baseline
     * (`choice_group` and its two link tables come from the V77 release marker), so nothing in the
     * window creates them — the same reason the stubs around this call exist. Shapes are copied
     * from [MigrationChainTest]'s seed rather than invented, so a column narrower than the real one
     * cannot let a migration pass here against a table production does not have.
     *
     * `item_extras`' two snapshot columns are deliberately created **without** defaults: that is the
     * Exposed-created shape the group's `ALTER COLUMN ... SET DEFAULT` exists to repair, so it does
     * real work here.
     */
    private fun seedCatalogGroupPreconditions(st: Statement) {
        // The group drops and re-creates the employees.info_id FK by looked-up name; without an
        // existing constraint its lookup finds nothing and the ON DELETE SET NULL swap is untested.
        st.execute(
            """
            ALTER TABLE employees
                ADD CONSTRAINT fk_employees_info_id__employee_info_id
                FOREIGN KEY (info_id) REFERENCES employee_info (employee_info_id)
            """.trimIndent(),
        )
        st.execute("CREATE TABLE item_transfer (id BIGSERIAL PRIMARY KEY, transfer_time TIMESTAMP NOT NULL)")
        st.execute(
            """
            CREATE TABLE product_extras (
                id        SERIAL PRIMARY KEY,
                name      VARCHAR(255)  NOT NULL,
                price     DECIMAL(19,4) NOT NULL,
                is_active BOOLEAN       NOT NULL DEFAULT TRUE
            )
            """.trimIndent(),
        )
        st.execute(
            """
            CREATE TABLE product_extras_assignment (
                product_id BIGINT  NOT NULL REFERENCES product(id),
                extras_id  INTEGER NOT NULL REFERENCES product_extras(id),
                PRIMARY KEY (product_id, extras_id)
            )
            """.trimIndent(),
        )
        st.execute("CREATE TABLE order_item (id BIGSERIAL PRIMARY KEY)")
        st.execute(
            """
            CREATE TABLE item_extras (
                id                   BIGSERIAL PRIMARY KEY,
                order_item_id        BIGINT NOT NULL REFERENCES order_item(id),
                product_extras_id    INTEGER REFERENCES product_extras(id),
                option_name_snapshot VARCHAR(255)  NOT NULL,
                price_delta_snapshot DECIMAL(19,4) NOT NULL,
                group_name_snapshot  VARCHAR(255),
                quantity             INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )
        st.execute(
            """
            CREATE TABLE choice_group (
                id             SERIAL PRIMARY KEY,
                name           VARCHAR(255) NOT NULL,
                en_name        VARCHAR(255),
                ch_name        VARCHAR(255),
                min_selections INTEGER   NOT NULL DEFAULT 0,
                max_selections INTEGER   NOT NULL DEFAULT 1,
                is_active      BOOLEAN   NOT NULL DEFAULT TRUE,
                updated_at     TIMESTAMP NOT NULL DEFAULT NOW(),
                CONSTRAINT chk_choice_group_sel CHECK (
                    min_selections >= 0 AND max_selections >= 1 AND min_selections <= max_selections
                )
            )
            """.trimIndent(),
        )
        st.execute(
            """
            CREATE TABLE choice_group_option (
                group_id      INTEGER NOT NULL REFERENCES choice_group(id) ON DELETE CASCADE,
                option_id     INTEGER NOT NULL REFERENCES product_extras(id) ON DELETE CASCADE,
                price_delta   DECIMAL(19,4) NOT NULL DEFAULT 0,
                sort_position INTEGER   NOT NULL DEFAULT 0,
                is_default    BOOLEAN   NOT NULL DEFAULT FALSE,
                updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
                PRIMARY KEY (group_id, option_id)
            )
            """.trimIndent(),
        )
        st.execute(
            """
            CREATE TABLE product_choice_group (
                product_id    BIGINT  NOT NULL REFERENCES product(id) ON DELETE CASCADE,
                group_id      INTEGER NOT NULL REFERENCES choice_group(id) ON DELETE CASCADE,
                sort_position INTEGER   NOT NULL DEFAULT 0,
                updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
                PRIMARY KEY (product_id, group_id)
            )
            """.trimIndent(),
        )
    }

    /**
     * Every migration version on disk above [baselineVersion], ascending. Gaps in the numbering
     * (V106–V108, reserved by an unmerged branch) are tolerated for free: this reads what Flyway
     * itself found, so an absent number is simply never in the list.
     */
    private fun migrationVersionsAboveBaseline(): List<String> {
        val baseline = MigrationVersion.fromVersion(baselineVersion)
        val versions =
            onDiskMigrations()
                .map { it.first }
                .filter { it > baseline }
                .sorted()
                .map { it.version }
        check(versions.isNotEmpty()) {
            "No migrations above V$baselineVersion found on the classpath at $migrationLocation"
        }
        return versions
    }

    /**
     * The version to baseline at — **derived from the scripts on disk**, in the spirit of
     * `CashBookMigrationRealDataCheck.resolveCashBookMigrationChain`: it is identified by what the
     * scripts *are*, not by the numbers they currently carry.
     *
     * The rule: **the highest release-marker migration that sits below the lowest squashed schema
     * group.** In plain terms, "the last release that shipped before anything in this window". That
     * boundary is meaningful rather than incidental — every migration this test is here to compare
     * lives above the last release — and it is what makes the test's hand-seeded base honest: the
     * seed stands in for the schema of the last released version, so everything after it must
     * genuinely run.
     *
     * ### Why this survives the rename pass
     *
     * The squash parks its six groups at whatever numbers are free and a later pass renumbers them
     * as a set. Both halves of this rule are name-based, so neither moves with the numbers:
     * `__release_` marks a release marker, `__schema_` marks a squashed group.
     *
     * It also survives the *next* release. When `V8x__release_1_1_0.sql` lands at the top of the
     * chain it is above the groups, so the "below the lowest schema group" clause excludes it and
     * the baseline stays at the last pre-window release — where the seed actually corresponds to
     * reality. A naive "highest release marker on disk" rule would jump to it, leave
     * [versionsAboveBaseline] empty, and quietly stop comparing anything.
     *
     * ### Why not a literal
     *
     * `BASELINE_VERSION = "85"` was the fifth instance of this branch's recurring defect: a literal
     * describing something that moves. It was chosen because V86 was the migration of interest at
     * the time, but it silently *excluded* every later group parked below it — the catalog group at
     * V81 carries `promotion.combinable`, which the compared [PromotionTable] declares, so Flyway
     * never created the column and the parity check reported drift that did not exist.
     */
    private fun resolveBaselineVersion(): String {
        val onDisk = onDiskMigrations()
        check(onDisk.isNotEmpty()) { "no versioned migrations on the classpath at $migrationLocation" }

        val lowestSquashedGroup =
            onDisk.filter { SQUASHED_GROUP_SCRIPT in it.second }.minByOrNull { it.first }?.first
        checkNotNull(lowestSquashedGroup) {
            "no squashed schema group ($SQUASHED_GROUP_SCRIPT) found at $migrationLocation — this " +
                "test's baseline is defined relative to them. Did the naming convention change?"
        }
        val baseline =
            onDisk
                .filter { RELEASE_MARKER_SCRIPT in it.second && it.first < lowestSquashedGroup }
                .maxByOrNull { it.first }
                ?.first
        checkNotNull(baseline) {
            "no release marker ($RELEASE_MARKER_SCRIPT) below the lowest squashed group " +
                "V${lowestSquashedGroup.version} at $migrationLocation — there is no meaningful " +
                "version to baseline at"
        }
        return baseline.version
    }

    /** Every versioned migration on the classpath as `version to scriptFileName`, ascending. */
    private fun onDiskMigrations(): List<Pair<MigrationVersion, String>> =
        Flyway
            .configure()
            .dataSource(pg.dataSource)
            .locations(migrationLocation)
            .load()
            .info()
            .all()
            .mapNotNull { info -> info.version?.let { it to info.script } }
            .sortedBy { it.first }

    private fun tableExists(table: String): Boolean =
        pg.dataSource.connection.use { conn ->
            conn
                .prepareStatement("SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = ?")
                .use { ps ->
                    ps.setString(1, table)
                    ps.executeQuery().use { it.next() }
                }
        }

    /** Every index on [table] as `(comma-joined column names in key order) to isUnique`. */
    private fun postgresIndexes(table: String): Set<Pair<String, Boolean>> =
        pg.dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT ix.indisunique AS uniq,
                           (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                              FROM unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord)
                              JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum) AS cols
                    FROM pg_class t
                    JOIN pg_index ix ON t.oid = ix.indrelid
                    WHERE t.relname = ? AND t.relnamespace = 'public'::regnamespace
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, table)
                    ps.executeQuery().use { rs ->
                        buildSet {
                            while (rs.next()) add(rs.getString("cols") to rs.getBoolean("uniq"))
                        }
                    }
                }
        }

    private companion object {
        /**
         * Filename marker of a release migration (`V80__release_1_0_10.sql`). The baseline is the
         * last of these below the window — see [resolveBaselineVersion].
         */
        const val RELEASE_MARKER_SCRIPT = "__release_"

        /**
         * Filename marker of a v1.1.0 squash group (`V81__schema_catalog_and_staff.sql`). These
         * are what the window contains, so the baseline must sit below the lowest of them.
         */
        const val SQUASHED_GROUP_SCRIPT = "__schema_"
    }
}
