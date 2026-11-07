/**
 * Schema management utility for automatic database table creation and migration.
 *
 * Provides centralized management of database schema initialization using Exposed ORM.
 * Automatically creates missing tables and handles dependencies between tables to ensure
 * proper foreign key constraint creation order.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.database

import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.audit.database.tables.EntryLogTable
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.bank.data.repository.BankAccountRepositoryImpl
import org.example.project.features.bank.data.service.BankAccountSeeder
import org.example.project.features.bank.data.service.StatementAccountResolver
import org.example.project.features.bill.database.tables.BillNumberSequenceTable
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.customer.database.tables.CustomerAddressTable
import org.example.project.features.customer.database.tables.CustomerTable
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.data.service.InvoiceBackfill
import org.example.project.features.document.database.tables.DocumentLinkTable
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.database.tables.CashBookCloseTable
import org.example.project.features.kassenbuch.database.tables.CashBookTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchLineItemTable
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.ItemExtrasTable
import org.example.project.features.order.database.tables.ItemTransferTable
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.database.tables.OrderNumberSequenceTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.printer.database.tables.PrintJobHistoryTable
import org.example.project.features.product.database.tables.ChoiceGroupOptionTable
import org.example.project.features.product.database.tables.ChoiceGroupTable
import org.example.project.features.product.database.tables.FavouriteProductTable
import org.example.project.features.product.database.tables.ProductCategoryPrinterLocationTable
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductChoiceGroupTable
import org.example.project.features.product.database.tables.ProductExtrasAssignmentTable
import org.example.project.features.product.database.tables.ProductExtrasTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierBackfill
import org.example.project.features.table.database.tables.LocationTypeTable
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.features.tse.database.tables.TseExplanationTable
import org.example.project.features.tse.database.tables.TseExportArchiveTable
import org.example.project.features.tse.database.tables.TseLateSigningTable
import org.example.project.features.tse.database.tables.TseSigningQueueTable
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.features.voucher.database.tables.VoucherRedeemTable
import org.example.project.features.voucher.database.tables.VoucherTable
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.features.zBon.database.tables.EmployeeZBonPaymentBreakdownTable
import org.example.project.features.zBon.database.tables.EmployeeZBonSummaryTable
import org.example.project.features.zBon.database.tables.VatGroupTable
import org.example.project.features.zBon.database.tables.ZBonPaymentSummaryTable
import org.example.project.features.zBon.database.tables.ZBonStammTerminalTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.net.JarURLConnection
import javax.sql.DataSource

/**
 * Manages database schema initialization and migration operations.
 *
 * Handles automatic table creation with proper dependency ordering to ensure
 * foreign key constraints are created correctly. Provides methods for both
 * development and production schema management.
 */
object SchemaManager {
    private val logger = LoggerFactory.getLogger(SchemaManager::class.java)

    /** Classpath directory holding the Flyway migration scripts. */
    private const val MIGRATION_RESOURCE_DIR = "db/migration"

    /**
     * Filename shape of a release-marker migration: `V{N}__release_{major}_{minor}_{patch}.sql`.
     *
     * The three captured groups ARE the `release_version` primary key of the row the script
     * inserts, which is what lets [seedReleaseNotes] skip a release without parsing the SQL.
     */
    private val RELEASE_MARKER_PATTERN = Regex("""^V\d+__release_(\d+)_(\d+)_(\d+)\.sql$""")

    /**
     * All database tables in dependency order — [ManagedTables.all], which owns the list and its
     * ordering rules. Kept under this name so no caller of the host changes.
     */
    val allTables = ManagedTables.all

    /**
     * Creates all missing database tables if they don't already exist.
     *
     * Performs a comprehensive check of the database schema and creates any
     * missing tables in the correct dependency order. Safe to call multiple
     * times - will only create tables that don't already exist.
     *
     * @throws SQLException if table creation fails
     * @throws IllegalStateException if database is not initialized
     */
    fun createTables() {
        if (!DatabaseFactory.isInitialized()) {
            throw IllegalStateException("Database not initialized. Call DatabaseFactory.init() first.")
        }

        try {
            transaction(DatabaseFactory.getDatabase()) {
                logger.info("Starting database schema creation/verification...")

                // Create all tables - SchemaUtils.create() is idempotent
                // It will only create tables that don't already exist
                SchemaUtils.create(*allTables)
                // The foreign keys between areas, which the table objects no longer declare.
                CrossAreaForeignKeys.addMissing()

                // Verify table creation
                val createdTables = allTables.map { it.tableName }
                logger.info("Database schema verification completed. Tables: ${createdTables.joinToString(", ")}")

                // Log schema statistics
                logSchemaStatistics()
            }

            // After the commit: the reconciler works on its own connections, which must see the
            // tables. Runs on EVERY start, so an install made before an index was declared gains it.
            DeclaredIndexReconciler.reconcile(DatabaseFactory.getDatabase(), DatabaseFactory.getDataSource(), allTables.toList())

            logger.info("Database schema initialization completed successfully")
        } catch (e: Exception) {
            logger.error("Failed to create database schema", e)
            throw e
        }
    }

    fun addMissingTables() {
        if (!DatabaseFactory.isInitialized()) {
            throw IllegalStateException("Database not initialized. Call DatabaseFactory.init() first.")
        }

        try {
            transaction(DatabaseFactory.getDatabase()) {
                logger.info("Adding missing tables to existing database...")

                if (!tablesExist()) {
                    logger.info("No tables exist. Creating tables...")
                    createTables()
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to add missing tables", e)
            throw e
        }
    }

    /**
     * Creates only missing columns for existing tables.
     *
     * Useful for schema migrations where tables exist but new columns
     * have been added to table definitions. More conservative than
     * createTables() as it only adds missing columns.
     */
    fun addMissingColumns() {
        if (!DatabaseFactory.isInitialized()) {
            throw IllegalStateException("Database not initialized. Call DatabaseFactory.init() first.")
        }

        try {
            transaction(DatabaseFactory.getDatabase()) {
                logger.info("Adding missing columns to existing tables...")

                SchemaUtils.addMissingColumnsStatements(*allTables).forEach { statement ->
                    logger.debug("Executing schema migration: $statement")
                    exec(statement)
                }

                logger.info("Missing columns added successfully")
            }
        } catch (e: Exception) {
            logger.error("Failed to add missing columns", e)
            throw e
        }
    }

    fun resetTables(withProduct: Boolean) {
        if (!DatabaseFactory.isInitialized()) {
            throw IllegalStateException("Database not initialized. Call DatabaseFactory.init() first.")
        }

        val productTables =
            arrayOf(
                FavouriteProductTable, // Depends on ProductTable
                ProductChoiceGroupTable, // Depends on ProductTable + ChoiceGroupTable
                ProductTable,
                ChoiceGroupOptionTable, // Depends on ChoiceGroupTable + ProductExtrasTable
                ChoiceGroupTable,
                ProductExtrasTable,
                ProductCategoryTable,
                ProductCategoryPrinterLocationTable,
                ProductExtrasAssignmentTable,
            )

        val otherTables =
            arrayOf(
                TableSessionTable, // Create before RTableTable since RTableTable references it
                CustomerTable,
                CustomerAddressTable,
                // Order system (depends on Employee, RTable, Product, VAT)
                OrdersTable,
                OrderNumberSequenceTable, // Reset with the orders it numbers
                OrderItemTable,
                CancellationTable, // Depends on OrderItem, Employee, TseTransaction
                ItemTransferTable,
                ItemExtrasTable,
                // Voucher system (depends on Orders)
                VoucherTable, // Depends on OrdersTable
                VoucherRedeemTable, // Depends on VoucherTable
                // Bill and Payment system (depends on Employee, VAT)
                BillTable,
                BillNumberSequenceTable, // Reset with the bills it numbers
                BillVatDetailTable,
                PaymentTable,
                // Promotion system (depends on Bill, OrderItem, Employee)
                PromotionTable,
                BillPromotionTable,
                // Voucher system (depends on Orders)
                VoucherTable, // Depends on OrdersTable
                VoucherRedeemTable, // Depends on VoucherTable
                // Report system (depends on Employee, Shop, TSE)
                TseTransactionTable,
                TseSigningQueueTable, // Depends on TseTransactionTable - must be after it
                TseLateSigningTable, // Depends on TseTransactionTable - must be after it
                TseExplanationTable, // Depends on TseTransactionTable - must be after it (design 2026-09-22 D7)
                TseExportArchiveTable, // Reset with the TSE tables it belongs to
                ZBonTable,
                ZBonStammTerminalTable, // Depends on ZBonTable
                VatGroupTable,
                ZBonPaymentSummaryTable,
                CashBookTable,
                KassenbuchHeaderTable,
                CashBookCloseTable, // Depends on CashBookTable, KassenbuchHeaderTable, Employee - must be after them
                KassenbuchLineItemTable,
                EmployeeTipPayoutTable, // Depends on ZBon, Employee, KassenbuchHeader - must be after them
                EmployeeZBonSummaryTable,
                EmployeeZBonPaymentBreakdownTable,
                DocumentLinkTable,
                TransactionAuditLogTable,
                EntryLogTable,
                TerminalTable,
                PrintJobHistoryTable,
            )

        val tables =
            if (withProduct) {
                productTables + otherTables
            } else {
                otherTables
            }

        try {
            transaction(DatabaseFactory.getDatabase()) {
                logger.info("Resetting tables")
                val reversedTables = tables.reversedArray()

                CrossAreaForeignKeys.dropExisting(reversedTables.toList())
                SchemaUtils.drop(*reversedTables)
                logger.info("Tables dropped")

                logger.info("create dropped tables")
                SchemaUtils.create(*reversedTables)
                CrossAreaForeignKeys.addMissing()
                logger.info("Tables created")

                RTable.find { RTableTable.tableStatus neq TableStatus.FREE }.forEach {
                    it.tableStatus = TableStatus.FREE
                    it.currentSession = null
                }

                return@transaction true
            }
        } catch (e: Exception) {
            logger.error("Failed to reset tables", e)
            throw e
        }
    }

    /**
     * Drops all application tables from the database, including orphaned tables.
     *
     * **DANGEROUS OPERATION** - This will permanently delete all data.
     * Only use for development, testing, or complete system resets.
     *
     * This method drops:
     * 1. Orphaned tables that no longer have table definitions
     * 2. All managed tables in reverse dependency order to avoid foreign key conflicts
     *
     * @param confirmDrop Must be true to execute the operation
     * @throws IllegalArgumentException if confirmDrop is false
     */
    fun dropAllTables(confirmDrop: Boolean = false): Boolean {
        if (!confirmDrop) {
            throw IllegalArgumentException("Must explicitly confirm table dropping by setting confirmDrop = true")
        }

        if (!DatabaseFactory.isInitialized()) {
            throw IllegalStateException("Database not initialized. Call DatabaseFactory.init() first.")
        }

        try {
            transaction(DatabaseFactory.getDatabase()) {
                logger.warn("DROPPING ALL TABLES - This will delete all data!")

                // Then drop managed tables in reverse order to avoid foreign key constraint issues
                val reversedTables = allTables.reversedArray()
                CrossAreaForeignKeys.dropExisting(reversedTables.toList())
                SchemaUtils.drop(*reversedTables)

                logger.warn("All application tables (managed and orphaned) have been dropped")
                return@transaction true
            }
        } catch (e: Exception) {
            logger.error("Failed to drop tables", e)
            throw e
        }
        return false
    }

    /**
     * Recreates all tables (drop and create).
     *
     * **DANGEROUS OPERATION** - This will permanently delete all data.
     * Useful for development when you need to reset the entire schema.
     *
     * @param confirmRecreate Must be true to execute the operation
     */
    fun recreateAllTables(confirmRecreate: Boolean = false): Boolean {
        if (!confirmRecreate) {
            throw IllegalArgumentException("Must explicitly confirm table recreation by setting confirmRecreate = true")
        }

        logger.warn("Recreating all tables - this will delete all existing data!")
        dropAllTables(confirmDrop = true)
        createTablesAndBootstrap()
        logger.info("All tables recreated successfully")
        return true
    }

    /**
     * Checks if all required tables exist in the database.
     *
     * @return true if all application tables exist, false otherwise
     */
    fun tablesExist(): Boolean {
        if (!DatabaseFactory.isInitialized()) {
            return false
        }

        return try {
            transaction(DatabaseFactory.getDatabase()) {
                allTables.all { table ->
                    table.exists()
                }
            }
        } catch (e: Exception) {
            logger.debug("Error checking table existence", e)
            false
        }
    }

    /**
     * Gets a list of all table names managed by this schema.
     *
     * @return List of table names in creation order
     */
    fun getTableNames(): List<String> = allTables.map { it.tableName }

    /**
     * Creates tables and performs initial system bootstrap.
     *
     * This method creates all missing tables and then performs initial system setup
     * such as creating the initial admin user if configured and no employees exist.
     *
     * @param performBootstrap If true, performs initial system bootstrap after table creation
     */
    fun createTablesAndBootstrap(performBootstrap: Boolean = true) {
        if (!DatabaseFactory.isInitialized()) {
            throw IllegalStateException("Database not initialized. Call DatabaseFactory.init() first.")
        }

        try {
            // First create all tables
            createTables()

            // Then perform bootstrap operations
            if (performBootstrap) {
                bootstrapInitialData()
            }
        } catch (e: Exception) {
            logger.error("Failed to create tables and bootstrap system", e)
            throw e
        }
    }

    /**
     * Performs initial system bootstrap operations.
     *
     * Creates essential system data like the initial admin user if configured
     * and the database is empty. This method is safe to call multiple times.
     */
    fun bootstrapInitialData() {
        if (!DatabaseFactory.isInitialized()) {
            throw IllegalStateException("Database not initialized. Call DatabaseFactory.init() first.")
        }

        try {
            logger.info("Starting system bootstrap...")

            // Seed well-known location types (INDOOR, OUTDOOR, TAKEAWAY, VOUCHER, QUICK_SALE)
            transaction(DatabaseFactory.getDatabase()) {
                if (org.example.project.features.table.domain.models.LocationType
                        .count() == 0L
                ) {
                    val now =
                        org.example.project.utils.formatter
                            .currentTimeStamp()

                    data class LtSeed(
                        val name: String,
                        val en: String,
                        val cn: String,
                        val virtual: Boolean,
                        val takeaway: Boolean,
                        val sort: Int,
                        val system: Boolean = false,
                    )
                    listOf(
                        LtSeed("Innenbereich", "Indoor", "室内", false, false, 1),
                        LtSeed("Außenbereich", "Outdoor", "户外", false, false, 2),
                        LtSeed("Abholung", "Takeaway", "外卖", false, true, 3),
                        LtSeed("Gutschein", "Voucher", "代金券", true, false, 4, system = true),
                        LtSeed("Schnellverkauf", "Quick Sale", "快速销售", true, false, 5, system = true),
                    ).forEach { seed ->
                        LocationTypeTable.insertIgnore {
                            it[LocationTypeTable.name] = seed.name
                            it[englishName] = seed.en
                            it[chineseName] = seed.cn
                            it[isVirtual] = seed.virtual
                            it[isTakeaway] = seed.takeaway
                            it[sortOrder] = seed.sort
                            it[isSystem] = seed.system
                            it[createdAt] = now
                            it[updatedAt] = now
                        }
                    }
                    logger.info("Seeded 5 default location types")
                }
                // Reconcile the system flag on rows that predate it (a persisted dev database the
                // Flyway V131 backfill never reached); idempotent on a fresh seed.
                LocationTypeTable.update({
                    (LocationTypeTable.id inList org.example.project.core.utils.SystemRecords.LOCATION_TYPE_IDS) and
                        (LocationTypeTable.isSystem eq false)
                }) { it[isSystem] = true }
            }

            // Seed the four system permission groups.
            //
            // Production gets these rows from Flyway V110. The H2 test schema is built by Exposed's
            // SchemaUtils.create and NEVER runs Flyway, so without this the groups would exist as
            // empty tables in every test. Seeding both paths from the SAME source —
            // CapabilityBundles — is what keeps them identical, and SeededGroupsMatchBundlesTest is
            // the standing guard that they still are.
            transaction(DatabaseFactory.getDatabase()) {
                val now =
                    org.example.project.utils.formatter
                        .currentTimeStamp()

                // Iterates DEFINITIONS, not EmployeeRole.entries: since Stage 3 the seeded groups
                // include ACCOUNTANT, AUDITOR and ORDER_ONLY, which have no role to be derived from.
                //
                // RECONCILES rather than inserts. This used to be `insertIgnore` only, which added
                // capabilities but never removed them — so on a database that already had rows, the
                // REMOVALS the migrations perform never applied. V111 deletes REPORT_READ and
                // DOCUMENT_READ from TERMINAL_DEVICE to close defect D4; against a persisted
                // `.dev-data` H2 database the old code left both in place, and a dev environment
                // silently kept the exposure the release had closed. The name/description went stale
                // the same way.
                //
                // SeededGroupsMatchBundlesTest could not catch it: TestDatabase builds a fresh
                // `h2:mem:test_<nanoTime>` per run, so the stale-row case never arose under test.
                // `SchemaManagerReconcilesGroupsTest` seeds, mutates and re-seeds in one database to
                // exercise exactly that path.
                CapabilityBundles.definitions().forEach { definition ->
                    val existing =
                        PermissionGroupTable
                            .selectAll()
                            .firstOrNull { it[PermissionGroupTable.code] == definition.code }

                    val groupId =
                        existing?.get(PermissionGroupTable.id)
                            ?: PermissionGroupTable.insertAndGetId {
                                it[code] = definition.code
                                it[name] = definition.name
                                it[description] = definition.description
                                it[isSystem] = true
                                it[isDeletable] = false
                                it[createdAt] = now
                                it[updatedAt] = now
                            }

                    // Display metadata follows the definition, so a renamed group does not keep its
                    // old label forever on an upgraded database.
                    if (existing != null &&
                        (
                            existing[PermissionGroupTable.name] != definition.name ||
                                existing[PermissionGroupTable.description] != definition.description
                        )
                    ) {
                        PermissionGroupTable.update({ PermissionGroupTable.id eq groupId }) {
                            it[name] = definition.name
                            it[description] = definition.description
                            it[updatedAt] = now
                        }
                    }

                    val want = definition.capabilities.map { it.name }.toSet()
                    val have =
                        PermissionGroupCapabilityTable
                            .selectAll()
                            .filter { it[PermissionGroupCapabilityTable.group] == groupId }
                            .map { it[PermissionGroupCapabilityTable.capability] }
                            .toSet()

                    // Remove first, so a capability that moved between groups cannot be briefly held
                    // by both. Only rows for THIS group are touched — a group the operator added
                    // outside the definitions is left entirely alone.
                    (have - want).forEach { stale ->
                        PermissionGroupCapabilityTable.deleteWhere {
                            (PermissionGroupCapabilityTable.group eq groupId) and
                                (PermissionGroupCapabilityTable.capability eq stale)
                        }
                    }
                    (want - have).forEach { missing ->
                        PermissionGroupCapabilityTable.insertIgnore {
                            it[group] = groupId
                            it[capability] = missing
                        }
                    }
                }
            }

            seedFiscalCashBooks()

            // AFTER the groups: the terminal-device employee is put INTO the TERMINAL_DEVICE
            // group, which has to exist by the time it is assigned.
            seedTerminalDeviceEmployee()
            seedReleaseNotes()

            backfillLegacyInvoices()
            backfillSuppliers()
            seedBankAccountFromShopInfo()
            resolveUnassignedStatements()

            logger.info("System bootstrap completed successfully")
        } catch (e: Exception) {
            logger.error("Failed to bootstrap initial data", e)
            throw e
        }
    }

    /**
     * Gives every shop its fiscal Ladenkasse `cash_book` row.
     *
     * The row used to be created lazily — `CashBookRepository.fiscalBookFor` on the first sale,
     * Z-Bon or Kassenbuch read — so on a fresh install the back office's cash-book list showed only
     * a book the operator had added (e.g. a Tresor) and no Ladenkasse (E2E 2026-09-20). V84
     * backfilled shops that existed when it ran, but a fresh install baselines Flyway without running
     * its INSERTs, and the setup wizard now creates the book with the shop. This heals a shop set up
     * before that, on the next boot.
     *
     * Idempotent — `fiscalBookFor` reuses an existing fiscal book — so it runs on every boot. Before
     * setup there is no shop and it does nothing.
     */
    private fun seedFiscalCashBooks() {
        transaction(DatabaseFactory.getDatabase()) {
            val books = CashBookRepositoryImpl()
            ShopInfo.all().forEach { shop -> books.fiscalBookFor(shop) }
        }
    }

    /**
     * Ensures the shared login-less `terminal-device` employee exists and holds its capabilities.
     *
     * Same dual-path problem as the permission groups above, one layer further on. An upgraded
     * database gets this row from `V79__release_tse_audit_terminal.sql` steps 6a/6b; a **fresh**
     * install never runs it, because `initDB` builds the schema with Exposed and then calls
     * `FlywayConfig.baselineAtLatest`, which marks V79 applied without ever executing it. The row
     * was therefore missing on every fresh install and `TerminalEmployee.requireRow` answered 500
     * ("Login-less terminal-employee is not provisioned") — so a paired ORDER_STATION could never
     * open a session and sat on its "Serverinstallation ist unvollständig" screen forever. The
     * device-principal order path (`getDeviceTokenClaims`) resolves the same row and failed the same
     * way. The H2 test schema is built by the same fresh path.
     *
     * Idempotent, which is what makes it correct to call on every boot rather than only when the
     * database looks fresh.
     */
    private fun seedTerminalDeviceEmployee() {
        transaction(DatabaseFactory.getDatabase()) {
            val employee = TerminalEmployee.findRow() ?: adoptOrCreateTerminalDeviceEmployee()

            // Re-asserted on every boot, not only on creation. An employee in no permission group
            // resolves to NO capabilities — GroupCapabilityResolver fails closed by design — so a
            // row that exists but is ungrouped reaches nothing at all, and the 403 surfaces nowhere
            // near this code. That has been the same defect three times in this repo (see
            // NoDirectEmployeeCreationTest); re-asserting costs one indexed write per start.
            val grouped =
                EmployeeRepositoryImpl().setPermissionGroupByCode(
                    employee.id.value,
                    EmployeeRole.TERMINAL_DEVICE.name,
                )
            if (!grouped) {
                logger.error(
                    "No permission_group with code '{}' — the shared terminal employee is ungrouped " +
                        "and every order station will be refused.",
                    EmployeeRole.TERMINAL_DEVICE.name,
                )
            }
        }
    }

    /**
     * Mirrors V79 steps 6a/6b, in that order.
     *
     * 6a: adopt the earliest existing terminal-role employee as the shared account. TERMINAL_DEVICE
     * is a device role, not a person, so normalizing its username/number is safe — and adopting is
     * what stops a second TERMINAL_DEVICE row appearing beside a legacy one. Reached only while the
     * canonical username is still free, exactly like the migration's `NOT EXISTS` guard, because the
     * caller resolved [TerminalEmployee.findRow] to null first.
     *
     * 6b: nothing to adopt, so seed a fresh account.
     *
     * The password hash is the empty string, deliberately: no verifier accepts it, so the account
     * cannot be logged into. It exists only to give a login-less station a real employee id to
     * attribute orders to for fiscal purposes, and must never be a usable credential.
     */
    private fun adoptOrCreateTerminalDeviceEmployee(): Employee {
        val adoptable =
            Employee
                .find { EmployeeTable.role eq EmployeeRole.TERMINAL_DEVICE }
                .orderBy(EmployeeTable.createdAt to SortOrder.ASC)
                .firstOrNull()

        if (adoptable != null) {
            adoptable.username = TerminalEmployee.USERNAME
            adoptable.employeeNumber = TerminalEmployee.EMPLOYEE_NUMBER
            adoptable.updatedAt =
                org.example.project.utils.formatter
                    .currentTimeStamp()
            logger.info("Adopted employee {} as the shared terminal-device account", adoptable.id.value)
            return adoptable
        }

        val seeded =
            Employee.new {
                this.username = TerminalEmployee.USERNAME
                this.employeeNumber = TerminalEmployee.EMPLOYEE_NUMBER
                this.role = EmployeeRole.TERMINAL_DEVICE
                this.passwordHash = ""
            }
        logger.info("Seeded the shared login-less terminal-device employee")
        return seeded
    }

    /**
     * Applies the release-marker migrations' `changelog_release` INSERTs on the fresh-install path.
     *
     * Another instance of the same dual-path defect, and the one with no Kotlin-side data to seed
     * from: `V80__release_1_0_10.sql` and `V90__release_1_1_0.sql` are *data-only* migrations, so a
     * baselined fresh install skips them and the "What's new" panel stays permanently empty for
     * every release up to the baseline. Application code is read-only against that table, so
     * nothing else would ever fill it in.
     *
     * The marker SQL files stay the **single source** of the release notes (root CLAUDE.md rule 13)
     * — they are read off the classpath and executed verbatim rather than transcribed into Kotlin,
     * because a hand-copied second version of three-language release notes diverges from the
     * migrations the first time somebody edits one of them.
     *
     * Runs on both paths, like every other seeder here: on an upgraded database Flyway has already
     * inserted the rows and [releaseRowExists] skips each one, so this is a pair of indexed reads
     * per start.
     *
     * Never fatal, per marker. Release notes are cosmetic; a malformed or un-runnable marker must
     * cost the operator a panel, not a server that will not boot.
     *
     * **PostgreSQL only.** The markers are Flyway migrations written against the production
     * database, `::jsonb` casts and all, and running them anywhere else does not fail loudly — H2
     * accepts `'[...]'::jsonb` and stores the array as a JSON *string*, so the row lands looking
     * fine and every later read of it answers 400. Every real install is PostgreSQL (embedded or
     * external); H2 is the test suite's stand-in, and tests that need a release row make their own
     * with `TestData.createChangelogRelease`. `ReleaseNotesSeededPostgresTest` covers the real path.
     */
    private fun seedReleaseNotes() {
        val dataSource = DatabaseFactory.getDataSource()
        if (!isPostgres(dataSource)) return

        releaseMarkerScripts().forEach { marker ->
            try {
                if (releaseRowExists(dataSource, marker.releaseVersion)) return@forEach

                val sql = readMigrationScript(marker.script)
                if (sql == null) {
                    logger.error("Release marker {} vanished from the classpath between scan and read", marker.script)
                    return@forEach
                }

                dataSource.connection.use { connection ->
                    connection.createStatement().use { it.execute(sql) }
                    // Hikari's autoCommit follows application.yaml, so it is not safe to assume
                    // either setting: an uncommitted INSERT here would be rolled back on close and
                    // the panel would stay empty with nothing in the log to say why.
                    if (!connection.autoCommit) connection.commit()
                }
                logger.info("Seeded release notes for {} from {}", marker.releaseVersion, marker.script)
            } catch (e: Exception) {
                logger.error(
                    "Could not seed release notes from {} — the What's new panel will be missing this release",
                    marker.script,
                    e,
                )
            }
        }
    }

    /**
     * Whether this connection speaks PostgreSQL.
     *
     * Asked of the driver rather than of a config flag so it cannot disagree with the database
     * actually connected. A failure to answer is treated as "not PostgreSQL": skipping optional
     * seeding is the safe direction.
     */
    private fun isPostgres(dataSource: DataSource): Boolean =
        try {
            dataSource.connection.use { it.metaData.databaseProductName.contains("PostgreSQL", ignoreCase = true) }
        } catch (e: Exception) {
            logger.warn("Could not determine the database product; skipping release-note seeding", e)
            false
        }

    /**
     * One release-marker migration: the script that carries it and the release it describes.
     *
     * Internal, not private, so `ReleaseNotesSeededTest` can assert *which* scripts the filename
     * convention selects. The squashed schema groups are one regex character away from being
     * selected too, and running those would be far worse than not running anything.
     */
    internal data class ReleaseMarker(
        val script: String,
        val releaseVersion: String,
    )

    /**
     * The release-marker migrations on the classpath, oldest release first.
     *
     * Matching is by the [RELEASE_MARKER_PATTERN] filename convention rule 13 mandates, and it has
     * to be exact: `V77__release_core_features.sql`, `V78__release_fiscal_realignment.sql` and
     * `V79__release_tse_audit_terminal.sql` are the **squashed schema groups**, full of DDL, and
     * also start with `release_`. Replaying those on a fresh install would try to rebuild a schema
     * Exposed has already created. Requiring a `{major}_{minor}_{patch}` tail is what separates the
     * two, and it doubles as the `release_version` primary key the row will carry.
     */
    internal fun releaseMarkerScripts(): List<ReleaseMarker> =
        migrationScriptNames()
            .mapNotNull { name ->
                RELEASE_MARKER_PATTERN.matchEntire(name)?.let { match ->
                    ReleaseMarker(name, match.groupValues.drop(1).joinToString("."))
                }
            }.sortedWith(
                compareBy(
                    { it.releaseVersion.substringBefore('.').toInt() },
                    {
                        it.releaseVersion
                            .substringAfter('.')
                            .substringBefore('.')
                            .toInt()
                    },
                    { it.releaseVersion.substringAfterLast('.').toInt() },
                ),
            )

    /**
     * Every filename in the migration resource directory.
     *
     * Both branches are load-bearing. Development and the test suite read the directory off the
     * filesystem; the shipped server runs from a shadowJar, where the same resources are jar
     * entries and `File.list()` returns nothing at all — a `file:`-only implementation would seed
     * correctly on every developer machine and silently seed nothing in production, which is the
     * failure mode this whole function exists to fix.
     */
    private fun migrationScriptNames(): List<String> {
        val directory = SchemaManager::class.java.classLoader.getResource(MIGRATION_RESOURCE_DIR) ?: return emptyList()

        return when (directory.protocol) {
            "file" -> File(directory.toURI()).list()?.toList().orEmpty()
            "jar" ->
                (directory.openConnection() as JarURLConnection)
                    .apply { useCaches = false }
                    .jarFile
                    .use { jar ->
                        jar
                            .entries()
                            .asSequence()
                            .map { it.name }
                            .filter { it.startsWith("$MIGRATION_RESOURCE_DIR/") }
                            .map { it.substringAfterLast('/') }
                            .filter { it.isNotEmpty() }
                            .toList()
                    }
            else -> emptyList()
        }
    }

    /** Reads one migration script off the classpath, or null when it is not there. */
    private fun readMigrationScript(script: String): String? =
        SchemaManager::class.java.classLoader
            .getResourceAsStream("$MIGRATION_RESOURCE_DIR/$script")
            ?.use { it.readBytes().decodeToString() }

    /**
     * Whether `changelog_release` already describes this release.
     *
     * Plain JDBC rather than the Exposed DAO on purpose: the whole seeding path stays on raw
     * statements so the marker SQL — Postgres `::jsonb` casts and all — is executed exactly as
     * Flyway would execute it, with no Exposed column mapping in between to disagree about types.
     */
    private fun releaseRowExists(
        dataSource: DataSource,
        releaseVersion: String,
    ): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT 1 FROM changelog_release WHERE release_version = ?").use { statement ->
                statement.setString(1, releaseVersion)
                statement.executeQuery().use { it.next() }
            }
        }

    /**
     * Copies legacy `document_invoice_metadata` rows into the normalized invoice model.
     *
     * Lives here rather than in a Flyway migration because Flyway only runs against Postgres, while
     * an H2 or dev database is built straight from the Exposed definitions above — both need the
     * backfill, and both have reached this point with every table present.
     *
     * Idempotent, so running it on every start is a no-op after the first. Never fatal: the legacy
     * table is left intact, so a failure here costs nothing but a retry on the next start, and must
     * not stop the server from coming up. A degraded document search must not take the till
     * offline, so this stays a log line and never a startup gate.
     *
     * Logged at ERROR rather than WARN because the blast radius grew: the backfill is
     * all-or-nothing in a single transaction, and document search now reads the normalized model,
     * so one unmappable legacy row makes **every** pre-existing document invisible to the
     * vendor/invoice-number/amount filters — not merely stale in the DATEV export.
     */
    private fun backfillLegacyInvoices() {
        try {
            transaction(DatabaseFactory.getDatabase()) {
                InvoiceBackfill(DocumentRepositoryImpl()).run()
            }
        } catch (e: Exception) {
            logger.error(
                "Legacy invoice backfill FAILED — startup continues and the legacy rows are untouched, but until " +
                    "it succeeds the DATEV Eingangsrechnungen export is stale AND document search by vendor name, " +
                    "invoice number or amount will not find any pre-existing document. The backfill is " +
                    "all-or-nothing, so a single unmappable legacy row causes this; fix the row and restart.",
                e,
            )
        }
    }

    /**
     * Derives creditors from the invoices [backfillLegacyInvoices] has just produced.
     *
     * **Deliberately its own transaction, opened after that one committed.** Sharing one would
     * couple two independent sweeps: on Postgres a failed statement aborts the whole transaction, so
     * `SupplierBackfill`'s per-invoice `catch` would have nothing left to continue with, and the
     * rollback would take the invoice backfill's output down with it — the failure mode the message
     * above describes, triggered by an unrelated creditor problem. Both sweeps are idempotent, so
     * splitting them changes nothing on the happy path and isolates the damage otherwise.
     *
     * Never fatal, for the same reason as the invoice backfill: an unresolved creditor costs a
     * DATEV posting hint, not the till.
     */
    private fun backfillSuppliers() {
        try {
            transaction(DatabaseFactory.getDatabase()) {
                SupplierBackfill(SupplierRepositoryImpl()).run()
            }
        } catch (e: Exception) {
            logger.error(
                "Supplier backfill FAILED — startup continues and the invoices themselves are untouched, but " +
                    "pre-existing invoices stay without a creditor until it succeeds, so their DATEV account and " +
                    "BU key must be entered by hand. Fix the cause and restart; the sweep is idempotent.",
                e,
            )
        }
    }

    /**
     * Back-fills `bank_statement.bank_account_id` for statements `:accounting-banking` wrote on upload with the
     * account still unresolved. Runs after [seedBankAccountFromShopInfo] so the freshly seeded
     * account is already in the registry.
     *
     * **Deliberately its own transaction**, and never fatal: an unresolved statement is a work item,
     * not a defect, and an IBAN matching nothing is reported rather than turned into an account.
     */
    private fun resolveUnassignedStatements() {
        try {
            transaction(DatabaseFactory.getDatabase()) {
                StatementAccountResolver(BankAccountRepositoryImpl()).resolveUnassigned()
            }
        } catch (e: Exception) {
            logger.error(
                "Statement account resolution FAILED — startup continues; affected statements keep " +
                    "a NULL bank_account_id and can be resolved again later.",
                e,
            )
        }
    }

    /**
     * Seeds the first [org.example.project.features.bank.domain.models.BankAccount] from the
     * account `shop_info` already holds inline, so an existing installation arrives with its own
     * account known rather than an empty registry.
     *
     * **Deliberately its own transaction**, for the same reason as [backfillSuppliers]: sharing one
     * with the other backfills would let a failure here roll back their work too, and on Postgres a
     * failed statement poisons the whole transaction.
     *
     * Never fatal: an un-seeded account costs nothing but a manual entry later.
     */
    private fun seedBankAccountFromShopInfo() {
        try {
            transaction(DatabaseFactory.getDatabase()) {
                BankAccountSeeder(BankAccountRepositoryImpl()).run()
            }
        } catch (e: Exception) {
            logger.error(
                "Bank account seed from shop_info FAILED — startup continues, but the shop's own " +
                    "account must be entered by hand until it succeeds. Fix the cause and restart; " +
                    "the seed is idempotent.",
                e,
            )
        }
    }

    /**
     * Logs database schema statistics for monitoring and debugging.
     */
    private fun logSchemaStatistics() {
        try {
            val tableCount = allTables.size
            val tableNames = allTables.map { it.tableName }.joinToString(", ")

            logger.info("Schema statistics:")
            logger.info("  Total tables: $tableCount")
            logger.info("  Table names: $tableNames")

            // Log connection pool statistics
            val poolStats = DatabaseFactory.getConnectionPoolStats()
            if (poolStats.isNotEmpty()) {
                logger.info("  Connection pool: ${poolStats["activeConnections"]}/${poolStats["maximumPoolSize"]} active")
            }
        } catch (e: Exception) {
            logger.debug("Could not retrieve schema statistics", e)
        }
    }
}
