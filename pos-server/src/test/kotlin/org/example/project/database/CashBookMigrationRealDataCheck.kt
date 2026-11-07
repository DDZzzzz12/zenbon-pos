package org.example.project.database

import org.example.project.testutils.PostgresTestDatabase
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.output.MigrateResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.sql.Connection
import java.util.zip.ZipInputStream
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Automates the migration core of the manual "Dataset A" checklist
 * (`docs/testing/manual-test-checklist-cash-book.md`) so it is repeatable rather than hand-run.
 *
 * ## What it proves
 *
 * The cash-book migration adds `kassenbuch_header.cash_book_id` as a NOT NULL FK, backfills it from
 * the new `cash_book` table, and adds `cash_book_close`. Every other automated test in this repo
 * starts from an EMPTY fixture, so nothing verifies what that backfill does to a real shop's
 * history. This check runs it over the **real production rows** of a backup archive.
 *
 * ## Technique (same as [MigrationChainTest])
 *
 * `initDB`'s fresh path baselines at the latest version and skips every migration, and the on-disk
 * migrations are incremental ALTERs on an Exposed-created base schema — so neither a from-empty
 * Flyway run nor `initDB` exercises the cash-book DDL. Instead this check:
 *
 * 1. starts zonky embedded PostgreSQL ([PostgresTestDatabase]);
 * 2. hand-builds the **pre-cash-book shape** of exactly the tables that migration touches —
 *    `shop_info`, `kassenbuch_header` (**without** `cash_book_id` / `counterparty_book_id` /
 *    `transfer_id`), a minimal `employees` for the `cash_book_close.closed_by` FK, and no
 *    `cash_book` at all. The column lists match the backup CSV headers **exactly** so `COPY` works;
 * 3. loads the REAL rows straight out of the archive's `data/shop_info.csv` and
 *    `data/kassenbuch_header.csv` via the COPY protocol — the era-correct restore, achieved in-test
 *    (the archive predates the cash-book dimension, so its CSV carries precisely the pre-cash-book
 *    columns);
 * 4. drives Flyway across exactly the cash-book slice, so it **genuinely executes** over those rows
 *    — asserted below, because a Flyway misconfiguration that skipped it would leave every other
 *    assertion vacuously green. The baseline, the target and the set of migrations that must run
 *    are all **derived from the migrations on disk** ([resolveCashBookMigrationChain]), never
 *    written down as version literals: this file has already lost a hard-coded row census and a
 *    hard-coded document-number census to exactly that rot, and the V84+V85 squash into
 *    `V85__schema_cash_and_fiscal.sql` then broke the version literals too;
 * 5. asserts A2 / A3 / A3b / A4 of Dataset A. Every dataset-size number is **derived at run time**
 *    — from the row count `COPY` reports, and from a pre-migration snapshot of the
 *    Belegnummernkreis — never hard-coded, because the production archive grows with every backup
 *    and a literal census silently rots into a failure that masks the real assertions. The same
 *    four checks exist as SQL in `docs/testing/sql/cash-book-migration-checks.sql` — keep them in
 *    sync.
 *
 * ## Deliberate omissions
 *
 * The hand-built tables carry **no** FK constraints to tables outside this slice (`employee_id`,
 * `zbon_id`, `linked_document_id`, `tse_transaction_id` all reference tables this check does not
 * create). That is deliberate: the subject under test is the cash-book backfill DML, not the whole
 * schema. `employees` exists only because the `cash_book_close.closed_by` FK needs a target.
 *
 * A "the next Belegnummer continues the series" assertion is deliberately **absent**: `beleg_nr` is
 * `YYYY-MM-NNNN` and month-scoped, the archive holds zero `2026-08` entries, so in August a working
 * and a broken backfill both produce `2026-08-0001` — such an assertion cannot fail. A3/A3b carry
 * the weight.
 *
 * PII: the archive is never committed, CI never runs this (`@Tag("realdata")`, see the
 * `realdataTest` Gradle task), and only COUNTS are ever logged — never a row value. Skips loudly
 * when the archive is absent, so it is never vacuously green.
 */
@Tag("realdata")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cash-book dimension migrations over REAL backup rows — Dataset A (local only)")
class CashBookMigrationRealDataCheck {
    private val logger = LoggerFactory.getLogger(CashBookMigrationRealDataCheck::class.java)
    private val migrationLocation = "classpath:db/migration"

    private var pg: PostgresTestDatabase? = null
    private lateinit var migrate: MigrateResult

    /** The derived cash-book slice this run drove Flyway across — see [resolveCashBookMigrationChain]. */
    private lateinit var chain: CashBookMigrationChain

    /**
     * The cash-book slice of the migration chain, computed from the scripts on the classpath.
     *
     * [requiredVersions] is every on-disk version in `(baselineVersion, targetVersion]` — i.e. every
     * migration Flyway is asked to run. Nothing here is a literal, so a renumbering, a squash or a
     * re-split of the cash-book migrations changes the values without touching this file.
     */
    private data class CashBookMigrationChain(
        val baselineVersion: String,
        val requiredVersions: List<String>,
    ) {
        /** The top of the slice — what Flyway is targeted at. */
        val targetVersion: String get() = requiredVersions.last()
    }

    /**
     * Ground truth for the dataset-size guard: the row count the COPY restore itself reported for
     * `kassenbuch_header`. Derived from the archive, never hard-coded — the production book only
     * grows, and a literal census here would go stale on every fresh backup (it did: the guard read
     * `273` while the archive had moved on, which fired BEFORE A3's two real assertions and left the
     * cash-book backfill unverified).
     */
    private var restoredHeaderRows: Long = -1

    /** Pre-migration snapshot of the Belegnummernkreis — see [a4_belegNummernkreisUntouched]. */
    private lateinit var belegNrBefore: BelegNrSeries

    /**
     * A whole-series fingerprint of `kassenbuch_header.beleg_nr`: the row count, the highest number,
     * and an MD5 over every number in order. The cash-book migrations must not touch a single one of
     * them, so
     * comparing this before/after the migration states the invariant directly instead of pinning a
     * census of one particular month in one particular archive.
     */
    private data class BelegNrSeries(
        val count: Long,
        val max: String?,
        val fingerprint: String?,
    )

    @BeforeAll
    fun restoreRealRowsThenMigrate() {
        val backupFile = locateBackupOrSkip()
        val shopInfoCsv = readZipEntry(backupFile, "data/shop_info.csv")
        val headerCsv = readZipEntry(backupFile, "data/kassenbuch_header.csv")

        val db = PostgresTestDatabase.start().also { pg = it }
        db.dataSource.connection.use { conn ->
            conn.autoCommit = true
            createPreCashBookSchema(conn)
            copyCsvInto(conn, "shop_info", shopInfoCsv)
            restoredHeaderRows = copyCsvInto(conn, "kassenbuch_header", headerCsv)
        }
        // Snapshot the Belegnummernkreis BEFORE Flyway runs, so A4 can assert the invariant
        // ("V84/V85 leave it untouched") rather than a census of one archive.
        belegNrBefore = readBelegNrSeries()
        logger.warn(
            "CashBookMigrationRealDataCheck: loaded REAL rows from {} — LOCAL ONLY, contains PII. " +
                "shop_info={}, kassenbuch_header={} (counts only; row values are never logged)",
            backupFile.absolutePath,
            rowCount("shop_info"),
            rowCount("kassenbuch_header"),
        )

        // Drive Flyway DIRECTLY. baselineOnMigrate + the derived baseline mark everything below the
        // cash-book slice as present — they are, for the slice we hand-built — and then the
        // cash-book migrations genuinely execute over the real rows.
        chain = resolveCashBookMigrationChain(db.dataSource)
        migrate =
            Flyway
                .configure()
                .dataSource(db.dataSource)
                .locations(migrationLocation)
                .baselineOnMigrate(true)
                .baselineVersion(chain.baselineVersion)
                .baselineDescription("pre-cash-book base (real backup rows restored by COPY)")
                .target(chain.targetVersion)
                .validateMigrationNaming(true)
                .load()
                .migrate()

        logger.info(
            "CashBookMigrationRealDataCheck: Flyway migrate -> success={}, migrationsExecuted={}, target={}, executed={}",
            migrate.success,
            migrate.migrationsExecuted,
            migrate.targetSchemaVersion,
            migrate.migrations.map { "V${it.version}" },
        )
    }

    @AfterAll
    fun tearDown() {
        pg?.close()
    }

    // ----------------------------------------------------------------------------------------
    // Genuine-execution proof — without this every assertion below could pass vacuously
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("The cash-book migrations genuinely EXECUTE over the real rows (not baseline-skipped)")
    fun migrationsGenuinelyExecute() {
        // WHY THIS GUARD EXISTS: baselining below the cash-book slice means Flyway will happily mark
        // a migration as below-baseline and skip it — and a skipped migration makes A2/A3/A3b/A4
        // vacuous, because the cash_book table and cash_book_id column they inspect would then be
        // whatever the fixture happened to create rather than what the migration produced.
        //
        // The floor is DERIVED, not written down: every on-disk migration in the cash-book slice must
        // appear in `executed`. The literal form ("84" and "85" and a count of exactly 2) broke the
        // moment those two files were squashed into V85 — a version census that fails on bookkeeping
        // rather than on any defect. This form survives a renumbering, a squash and a re-split.
        assertTrue(migrate.success, "Flyway migrate() must succeed")

        val executed = migrate.migrations.mapNotNull { it.version }.toSet()
        val skipped = chain.requiredVersions.filterNot { it in executed }
        assertTrue(
            skipped.isEmpty(),
            "Every migration above the V${chain.baselineVersion} baseline and up to the cash-book " +
                "target V${chain.targetVersion} must have EXECUTED, not been skipped; " +
                "skipped=$skipped, executed=$executed",
        )
        assertTrue(
            migrate.migrationsExecuted > 0,
            "Flyway executed NOTHING — the cash-book DDL was baseline-skipped and every assertion " +
                "below would be vacuous",
        )
        assertEquals(
            chain.requiredVersions.size,
            migrate.migrationsExecuted,
            "exactly the cash-book slice ${chain.requiredVersions} must have run; " +
                "migrationsExecuted=${migrate.migrationsExecuted}",
        )
        assertEquals(
            chain.targetVersion,
            migrate.targetSchemaVersion,
            "the applied target must be the top of the cash-book slice",
        )

        // Cross-check via info(): each one is SUCCESS (applied), not BELOW_BASELINE / BASELINE.
        val states =
            Flyway
                .configure()
                .dataSource(requirePg().dataSource)
                .locations(migrationLocation)
                .baselineVersion(chain.baselineVersion)
                .load()
                .info()
                .all()
                .filter { it.version?.version in chain.requiredVersions }
                .associate { it.version!!.version to it.state }
        val notApplied = states.filterValues { it != MigrationState.SUCCESS }
        assertEquals(
            chain.requiredVersions.toSet(),
            states.keys,
            "Flyway.info() must report every cash-book migration; got ${states.keys}",
        )
        assertTrue(
            notApplied.isEmpty(),
            "every cash-book migration must be in state SUCCESS (proves it was executed, not " +
                "baselined away); offending=$notApplied",
        )
    }

    // ----------------------------------------------------------------------------------------
    // Deriving the cash-book slice from the migrations on disk
    // ----------------------------------------------------------------------------------------

    /**
     * Locates the cash-book migrations on the classpath and derives the whole Flyway configuration
     * from them: the baseline is the on-disk version immediately BELOW the first of them, the target
     * is the last of them, and everything in between must run.
     *
     * "Cash-book migration" is decided by what the script *does* — it creates a `cash_book…` table —
     * not by its number, which is the only fact that survives renumbering. Historically that was
     * V84 (`cash_book` + the backfill) plus V85 (`cash_book_close`); after the squash it is the
     * single `V85__schema_cash_and_fiscal.sql`. Both shapes resolve correctly here, and so would a
     * future re-split, because nothing about the answer is written down.
     *
     * The baseline must be a real on-disk version rather than `firstCashBook - 1`: migration numbers
     * are not dense (V88 and V99–V108 are absent on this line), and Flyway's `baselineVersion` marks
     * everything at or below it as present, so an arbitrary number between two migrations would
     * quietly change which scripts are skipped.
     */
    private fun resolveCashBookMigrationChain(dataSource: DataSource): CashBookMigrationChain {
        val onDisk =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations(migrationLocation)
                .load()
                .info()
                .all()
                .mapNotNull { info -> info.version?.let { it to info.script } }
                .sortedBy { it.first }
        check(onDisk.isNotEmpty()) { "no versioned migrations on the classpath at $migrationLocation" }

        val cashBookIndices = onDisk.indices.filter { createsACashBookTable(onDisk[it].second) }
        check(cashBookIndices.isNotEmpty()) {
            "no migration on the classpath at $migrationLocation creates a cash_book table — this " +
                "check has nothing to verify. Did the cash-book DDL move out of db/migration?"
        }
        val firstIndex = cashBookIndices.first()
        check(firstIndex > 0) {
            "the first cash-book migration ${onDisk[firstIndex].second} is the LOWEST on disk, so " +
                "there is no version to baseline at below it"
        }
        val baseline = onDisk[firstIndex - 1].first
        val target = onDisk[cashBookIndices.last()].first
        val required = onDisk.map { it.first }.filter { it > baseline && it <= target }.map { it.version }

        logger.info(
            "CashBookMigrationRealDataCheck: derived cash-book slice baseline=V{}, target=V{}, required={}",
            baseline.version,
            target.version,
            required,
        )
        return CashBookMigrationChain(baselineVersion = baseline.version, requiredVersions = required)
    }

    /** True when [script] contains a `CREATE TABLE cash_book…` — the marker of the slice under test. */
    private fun createsACashBookTable(script: String): Boolean {
        val sql =
            javaClass.getResourceAsStream("/db/migration/$script")?.use { it.readBytes().decodeToString() }
                ?: error("migration script '$script' is on Flyway's classpath but not readable as a resource")
        return CASH_BOOK_CREATE.containsMatchIn(sql)
    }

    // ----------------------------------------------------------------------------------------
    // Dataset A assertions — numbers measured from the archive (mirror of
    // docs/testing/sql/cash-book-migration-checks.sql)
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("A2 — the migration creates exactly one fiscal Ladenkasse, and no other book")
    fun a2_oneFiscalCashBook() {
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM cash_book WHERE is_fiscal"), "fiscal books")
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM cash_book"), "total books")
    }

    /**
     * Lowest plausible size for the production Kassenbuch this check restores. Not a census: the
     * archive held 328 headers in 2026-08 and the book only ever grows, so this floor never needs
     * touching — it exists solely so a restore that silently loaded nothing (or a truncated one)
     * cannot make the two assertions below pass vacuously with `COUNT(*) = 0`.
     */
    private val minimumPlausibleHeaderRows = 100L

    @Test
    @DisplayName("A3 — every historical header resolves to a cash book that exists")
    fun a3_everyHeaderResolves() {
        // --- dataset-size guard (derived, never a literal census) ---------------------------------
        // Without it, an empty restore would satisfy both real assertions below with zero rows.
        val totalHeaders = scalarLong("SELECT COUNT(*) FROM kassenbuch_header")
        assertTrue(
            restoredHeaderRows >= minimumPlausibleHeaderRows,
            "the COPY restore loaded $restoredHeaderRows kassenbuch_header row(s) from the archive — " +
                "below the $minimumPlausibleHeaderRows-row floor for a real production book. The " +
                "assertions below would pass vacuously, so this check refuses to run on an empty or " +
                "truncated restore.",
        )
        assertEquals(
            restoredHeaderRows,
            totalHeaders,
            "the cash-book migrations must neither add nor drop headers; COPY reported " +
                "$restoredHeaderRows row(s)",
        )
        logger.info(
            "CashBookMigrationRealDataCheck: A3 runs against {} real kassenbuch_header rows.",
            totalHeaders,
        )

        // --- the assertions that actually prove the cash-book backfill ------------------------------------
        assertEquals(
            0L,
            scalarLong("SELECT COUNT(*) FROM kassenbuch_header WHERE cash_book_id IS NULL"),
            "headers with a NULL cash_book_id",
        )
        assertEquals(
            0L,
            scalarLong(
                "SELECT COUNT(*) FROM kassenbuch_header h " +
                    "LEFT JOIN cash_book cb ON cb.id = h.cash_book_id WHERE cb.id IS NULL",
            ),
            "headers pointing at a non-existent cash book (orphaned)",
        )
    }

    @Test
    @DisplayName("A3b — no header is filed under a non-fiscal book")
    fun a3b_noHeaderInNonFiscalBook() {
        assertEquals(
            0L,
            scalarLong(
                "SELECT COUNT(*) FROM kassenbuch_header h " +
                    "JOIN cash_book cb ON cb.id = h.cash_book_id WHERE NOT cb.is_fiscal",
            ),
            "headers filed under a non-fiscal book",
        )
    }

    @Test
    @DisplayName("A4 — the existing Belegnummernkreis is untouched")
    fun a4_belegNummernkreisUntouched() {
        // Compare the WHOLE series before/after the migration. The old form pinned one archive's
        // July census (75 entries topping out at 2026-07-0075), which both went stale and was
        // weaker: it said nothing about the other months. This states the invariant itself.
        assertTrue(
            belegNrBefore.count >= minimumPlausibleHeaderRows,
            "the pre-migration Belegnummernkreis held only ${belegNrBefore.count} entries — below the " +
                "$minimumPlausibleHeaderRows-row floor; an empty restore cannot prove anything here.",
        )
        assertEquals(
            belegNrBefore,
            readBelegNrSeries(),
            "the Belegnummernkreis must survive the cash-book migrations unchanged",
        )
    }

    /** Reads the [BelegNrSeries] fingerprint. The MD5 keeps whole document numbers out of logs. */
    private fun readBelegNrSeries(): BelegNrSeries =
        BelegNrSeries(
            count = scalarLong("SELECT COUNT(*) FROM kassenbuch_header WHERE beleg_nr IS NOT NULL"),
            max = scalarString("SELECT MAX(beleg_nr) FROM kassenbuch_header"),
            fingerprint =
                scalarString(
                    "SELECT md5(COALESCE(string_agg(beleg_nr, ',' ORDER BY beleg_nr), '')) " +
                        "FROM kassenbuch_header",
                ),
        )

    // ----------------------------------------------------------------------------------------
    // Backup access
    // ----------------------------------------------------------------------------------------

    /** Same convention as [RealDataMigrationCheck]: env `POS_TEST_BACKUP`, else `test-data/…`. */
    private fun locateBackupOrSkip(): File {
        val backupPath = System.getenv("POS_TEST_BACKUP") ?: "test-data/real-backup.zip"
        val backupFile = File(backupPath)
        Assumptions.assumeTrue(backupFile.exists() && backupFile.canRead()) {
            "SKIPPED CashBookMigrationRealDataCheck: no readable backup at '$backupPath'. Set " +
                "POS_TEST_BACKUP or drop the zip at test-data/real-backup.zip. This is a local-only " +
                "check — CI never runs it (the backup carries PII that must never be committed)."
        }
        return backupFile
    }

    private fun readZipEntry(
        zipFile: File,
        entryName: String,
    ): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipFile.readBytes())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == entryName) return zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        error("backup ${zipFile.name} has no entry '$entryName'")
    }

    /**
     * Imports a backup CSV with the same statement shape the real restore uses
     * (`COPY … FROM STDIN WITH (FORMAT csv, HEADER true)`), taking the column list from the CSV's own
     * header line so the hand-built table must match the archive exactly.
     *
     * Returns the number of rows PostgreSQL actually copied. That is the archive's own ground truth
     * for the dataset size — more reliable than counting CSV lines (quoted fields may contain
     * newlines) and the reason no test below needs a hard-coded row census.
     */
    private fun copyCsvInto(
        conn: Connection,
        table: String,
        csv: ByteArray,
    ): Long {
        val columns =
            csv
                .decodeToString()
                .substringBefore('\n')
                .trim()
                .split(',')
                .joinToString(", ") { "\"$it\"" }
        val copyManager = conn.unwrap(PGConnection::class.java).copyAPI
        return copyManager.copyIn(
            "COPY \"$table\" ($columns) FROM STDIN WITH (FORMAT csv, HEADER true)",
            ByteArrayInputStream(csv),
        )
    }

    // ----------------------------------------------------------------------------------------
    // Pre-V84 schema
    // ----------------------------------------------------------------------------------------

    /**
     * Materialises the PRE-cash-book shape of exactly the tables the cash-book migrations touch.
     *
     * `shop_info` and `kassenbuch_header` carry the archive CSV's columns **in order and by name**
     * — that is what makes the COPY restore era-correct. `kassenbuch_header` deliberately lacks
     * `cash_book_id`, `counterparty_book_id` and `transfer_id`, and there is no `cash_book` table,
     * because those are exactly what the migration adds.
     *
     * DELIBERATE: no FK constraints on `employee_id` / `zbon_id` / `linked_document_id` /
     * `tse_transaction_id`. Their target tables live outside this slice and creating them would add
     * nothing — what is under test is the cash-book backfill DML, not the full schema. `employees`
     * is created (empty) only so the `cash_book_close.closed_by` FK has a target.
     */
    private fun createPreCashBookSchema(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE shop_info (
                    shop_id          BIGSERIAL PRIMARY KEY,
                    shop_name        VARCHAR(255) NOT NULL,
                    company_name     VARCHAR(255),
                    shop_address     VARCHAR(500),
                    shop_city        VARCHAR(100),
                    zip_code         INTEGER NOT NULL,
                    shop_country     VARCHAR(100),
                    shop_phone       VARCHAR(50),
                    shop_tax_number  VARCHAR(50),
                    shop_bank_name   VARCHAR(100),
                    shop_bank_bic    VARCHAR(20),
                    shop_bank_iban   VARCHAR(50),
                    shop_email       VARCHAR(255),
                    shop_timezone    VARCHAR(50) NOT NULL DEFAULT 'Europe/Berlin',
                    shop_website     VARCHAR(255),
                    restaurant_mode  BOOLEAN NOT NULL DEFAULT TRUE,
                    kiosk_mode       BOOLEAN NOT NULL DEFAULT FALSE,
                    logo_url         VARCHAR(500),
                    create_at        TIMESTAMP
                )
                """.trimIndent(),
            )
            // Column list == data/kassenbuch_header.csv header, minus nothing and plus nothing.
            st.execute(
                """
                CREATE TABLE kassenbuch_header (
                    id                  BIGSERIAL PRIMARY KEY,
                    shop_id             BIGINT NOT NULL,
                    employee_id         UUID,
                    entry_type          VARCHAR(50) NOT NULL,
                    beleg_nr            VARCHAR(30) NOT NULL,
                    beleg_datum         VARCHAR(20) NOT NULL,
                    belegtext           VARCHAR(255),
                    waehrung            VARCHAR(3),
                    bu                  VARCHAR(10),
                    gegenkonto          VARCHAR(20),
                    kost1               VARCHAR(36),
                    kost2               VARCHAR(36),
                    nachricht           VARCHAR(500),
                    total_amount        DECIMAL(19,4) NOT NULL,
                    current_balance     DECIMAL(19,4),
                    tse_transaction_id  BIGINT,
                    zbon_id             BIGINT,
                    tse_signing_status  VARCHAR(30),
                    linked_document_id  BIGINT,
                    is_locked           BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at          TIMESTAMP NOT NULL
                )
                """.trimIndent(),
            )
            // Empty — only the cash_book_close.closed_by FK needs this table to exist.
            st.execute("CREATE TABLE employees (employee_id UUID PRIMARY KEY)")
        }
    }

    // ----------------------------------------------------------------------------------------
    // Query helpers — every result is a COUNT or a Belegnummer, never a PII row value
    // ----------------------------------------------------------------------------------------

    private fun requirePg(): PostgresTestDatabase = pg ?: error("embedded PostgreSQL was not started")

    private fun rowCount(table: String): Long = scalarLong("SELECT COUNT(*) FROM \"$table\"")

    private fun scalarLong(sql: String): Long =
        requirePg().dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }

    private fun scalarString(sql: String): String? =
        requirePg().dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private companion object {
        /**
         * Matches `CREATE TABLE [IF NOT EXISTS] cash_book…`, so it recognises both `cash_book` itself
         * and `cash_book_close` however they are distributed over files.
         */
        private val CASH_BOOK_CREATE =
            Regex("""create\s+table\s+(if\s+not\s+exists\s+)?"?cash_book""", RegexOption.IGNORE_CASE)
    }
}
