package org.example.project.database

import com.zaxxer.hikari.HikariConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.export.dsfinvk.builder.KassenbuchDsfinvkMapping
import org.example.project.export.dsfinvk.builder.KassenbuchTaxClassifier
import org.example.project.features.backup.data.service.BackupRestoreServiceImpl
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.testJson
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.stopKoin
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.sql.Connection
import java.util.zip.ZipInputStream
import javax.sql.DataSource
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local-only, real-data integration check. **Cannot run in CI** (it needs a real backup that
 * carries PII) and skips loudly when the backup is absent — never vacuously green.
 *
 * This is the counterpart to [MigrationChainTest]. Where that test genuinely executes the fiscal
 * consolidation **V78** (formerly V94..V97) against a *synthetic* seed on embedded PG, this one
 * exercises it against a **real production backup**: real historical Kassenabschlüsse, real
 * frozen-timestamp rows, real Stammdaten — the exact data the round-7 fixes were written for.
 *
 * ## Two datasource modes (identical restore→migrate→export flow)
 *
 * 1. **Embedded zonky PostgreSQL (default fallback).** When `POS_TEST_PG_URL` is unset AND no
 *    Docker PG is reachable, the check stands up an ephemeral [PostgresTestDatabase] (the same
 *    zonky harness [MigrationChainTest] uses) and runs the whole flow against it. This lets the
 *    check run **anywhere a backup is present** — no Docker required — which is why it is the
 *    default. The embedded binary is **PostgreSQL 16.6**; the backup was taken on **16.13**. That
 *    minor mismatch is safe here: the migrations are standard `ALTER`/`UPDATE`/`LPAD` DML/DDL and
 *    the restore uses the **version-agnostic COPY protocol** ([BackupRestoreServiceImpl], no
 *    `pg_dump`/`pg_restore` binaries), so nothing depends on the server patch version.
 * 2. **Docker PostgreSQL (opt-in, persistent).** If `POS_TEST_PG_URL` is set, that external PG is
 *    used and the check skips loudly if it is unreachable. If `POS_TEST_PG_URL` is unset but a PG
 *    is already reachable at the default `localhost:5440`, that Docker instance is used. This keeps
 *    the pre-existing Docker path working for devs who want a persistent DB to inspect afterwards.
 *
 * ## Flow (both modes)
 *
 * 1. Read the backup zip (env `POS_TEST_BACKUP`, default `test-data/real-backup.zip`). Skip loudly
 *    if missing.
 * 2. Point [DatabaseFactory] at the chosen PG and create the CURRENT schema via
 *    `initDB(runMigrations = false)` — same schema-without-Flyway-history pattern as the H2
 *    [org.example.project.testutils.TestDatabase], but on real PostgreSQL.
 * 3. Restore the backup by REUSING [BackupRestoreServiceImpl.restoreBackup] (the `jdbc-copy-csv`
 *    COPY-protocol path) — no reimplementation. The pre-restore safety-backup mechanism is left
 *    intact; it is simply pointed at a temp dir (see [TempPathConfigManager]).
 * 4. **Replay the fiscal consolidation V78 over the real rows.** Baseline Flyway at **V77** and let
 *    it execute **V78** (the consolidated fiscal migration, formerly V94..V97). Its DDL is entirely
 *    `ADD COLUMN / CREATE TABLE / CREATE INDEX IF NOT EXISTS`, so it is a no-op against the
 *    already-current schema, while its DML runs over the real data: the Stammdaten backfill (gated
 *    by the C5/C15 `entry_log` precondition) and the `>5`-digit-PLZ repair. The real-data OUTCOME is
 *    REPORTED (counts only). This is the only slice of the chain that can be replayed here — a FULL
 *    replay from the backup's own version is structurally impossible (see the FINDING).
 * 5. Run a full DSFinV-K export over the real closings — the real payoff.
 * 6. Emit **non-PII diagnostics only** (row COUNTS, export byte size + file COUNT, structural
 *    invariant pass/fail booleans, and the R6-C1 disclosure COUNT). Never logs a row value.
 *
 * ## FINDING (2026-07-21, first real run) — a FULL replay is unreachable; only the fiscal
 * consolidation V78 (formerly V94..V97) can be replayed
 *
 * The original design (round 7) intended step 4 to run migrations **incrementally from the backup's
 * own version (V76)** all the way up to Vmax — the existing-DB upgrade path. That FULL replay is
 * **structurally impossible with this restore mechanism**, and the first real run proved it:
 * [BackupRestoreServiceImpl.restoreBackup] TRUNCATEs and COPYs the V76 CSV data into the **current**
 * Exposed schema that `initDB` already built (columns the old backup lacks take their defaults). So
 * post-restore `product_extras` already has `linked_product_id` and `zbon` already has the
 * `stamm_kasse_*` columns. A Flyway replay baselined at V76 therefore dies at the **first additive
 * migration, V77** (`ALTER TABLE product_extras ADD COLUMN linked_product_id` → SQLSTATE **42701
 * duplicate_column**). This is a flaw in the CHECK, not the migrations — production upgrades run
 * Flyway BEFORE Exposed on a genuine V76 DB, so the additive DDL applies cleanly there; only this
 * harness pre-materialises the current schema, so the non-idempotent additive DDL (V77, V79) cannot
 * be re-applied on top of it.
 *
 * **Superseded 2026-10-02 for the restore itself:** [BackupRestoreServiceImpl.restoreBackup] now
 * migrates an archive at Flyway ≤ 76 through the real V77..Vmax chain in a staging database built
 * from the frozen v1.0.10 schema (`RestoreStaging`) before loading it, so step 3 already yields the
 * upgraded rows. Step 4 below is a second, idempotent pass of V78 over them; it is kept because it
 * still reports the V78 outcome on real data.
 *
 * What IS reachable — and what step 4 now genuinely does — is replaying the **fiscal consolidation
 * V78** (formerly V94..V97) over the real rows. It was written to be re-runnable: its DDL is all
 * `IF NOT EXISTS` / idempotent (a no-op against the current schema) and its payload is DML.
 * Baselining at V77 makes Flyway execute it, putting the real data through the Stammdaten backfill
 * and the PLZ repair — the exact round-7 logic, on real `zbon` / `shop_info` / `entry_log` rows
 * rather than a synthetic seed. The check REPORTS the outcome (structural counts only): whether the
 * C5/C15 `entry_log` precondition held on the real audit trail and how many closings it backfilled,
 * and how many `shop_info` rows carry the `>5`-digit PLZ that the repair fixes (a no-op when none).
 * The synthetic [MigrationChainTest] (`@Tag("postgres")`, V77→Vmax on a seeded pre-state) remains the
 * deterministic pass/fail proof of the same migration; this check is the real-data complement. After
 * the replay, the DSFinV-K export runs over the real closings — the real cert-path payoff.
 *
 * PII: the backup is never committed (gitignored under `test-data/`), CI never uses it, and row
 * contents are never logged — only counts, versions, and byte sizes. See
 * `pos-server/src/test/README-real-data.md`.
 */
@Tag("realdata")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Real-data migration + DSFinV-K export — embedded zonky PG (Docker optional; local only)")
class RealDataMigrationCheck {
    private val logger = LoggerFactory.getLogger(RealDataMigrationCheck::class.java)
    private val migrationLocation = "classpath:db/migration"

    @Test
    @DisplayName("restore real backup, replay the fiscal consolidation V78 (formerly V94..V97) over the real rows, then DSFinV-K export")
    fun restoreThenMigrateThenExport() =
        runBlocking {
            // --- 1) Locate the backup; skip loudly if absent (never vacuous-green) -----------------
            val backupPath = System.getenv("POS_TEST_BACKUP") ?: "test-data/real-backup.zip"
            val backupFile = File(backupPath)
            Assumptions.assumeTrue(backupFile.exists() && backupFile.canRead()) {
                "SKIPPED RealDataMigrationCheck: no readable backup at '$backupPath'. Set POS_TEST_BACKUP " +
                    "or drop the zip at test-data/real-backup.zip. This is a local-only check — CI never " +
                    "runs it (the backup carries PII that must never be committed)."
            }
            logger.warn(
                "RealDataMigrationCheck: using real backup {} ({} bytes) — LOCAL ONLY, contains PII",
                backupFile.absolutePath,
                backupFile.length(),
            )

            // --- 2) Choose the datasource: explicit Docker > reachable Docker > embedded zonky ------
            // POS_TEST_PG_URL set          -> caller explicitly wants an external/Docker PG (skip loudly
            //                                 if unreachable — a persistent DB was requested on purpose).
            // unset + Docker reachable      -> use the already-running Docker PG at localhost:5440.
            // unset + Docker unreachable    -> fall back to embedded zonky so the check runs anywhere.
            val explicitPgUrl = System.getenv("POS_TEST_PG_URL")
            val dockerConfig = dockerHikariConfig()
            var embeddedPg: PostgresTestDatabase? = null
            val hikari: HikariConfig =
                when {
                    explicitPgUrl != null -> {
                        Assumptions.assumeTrue(dockerReachable(dockerConfig)) {
                            "SKIPPED RealDataMigrationCheck: POS_TEST_PG_URL='$explicitPgUrl' is set but that " +
                                "PostgreSQL is unreachable. Start it, or unset POS_TEST_PG_URL to fall back to " +
                                "the embedded zonky PostgreSQL (no Docker needed)."
                        }
                        logger.warn("RealDataMigrationCheck: mode=DOCKER (explicit POS_TEST_PG_URL) at {}", dockerConfig.jdbcUrl)
                        dockerConfig
                    }
                    dockerReachable(dockerConfig) -> {
                        logger.warn("RealDataMigrationCheck: mode=DOCKER (reachable) at {}", dockerConfig.jdbcUrl)
                        dockerConfig
                    }
                    else -> {
                        logger.warn(
                            "RealDataMigrationCheck: mode=EMBEDDED — no POS_TEST_PG_URL and Docker unreachable; " +
                                "starting an ephemeral zonky PostgreSQL 16.6 (backup was taken on 16.13 — safe: " +
                                "COPY-protocol restore + standard ALTER/UPDATE migrations are version-agnostic).",
                        )
                        PostgresTestDatabase.start().also { embeddedPg = it }.hikariConfig()
                    }
                }

            try {
                stopKoin() // mirror TestDatabase: this check drives services directly, no Koin container
                initDB(hikari, runMigrations = false) // CURRENT schema on real PG, NO Flyway history yet

                // --- 3) Restore via the real service, safety backup pointed at a temp dir (not disabled)
                val docDir = Files.createTempDirectory("posrealdata-docs").toFile().apply { deleteOnExit() }
                val safetyDir = Files.createTempDirectory("posrealdata-safety").toFile().apply { deleteOnExit() }
                val configManager = TempPathConfigManager(docDir.absolutePath, safetyDir.absolutePath)
                val restoreService =
                    BackupRestoreServiceImpl(
                        ConfigurationRepository(),
                        configManager,
                        org.example.project.database
                            .LiveDataSourceProvider {
                                org.example.project.database.DatabaseFactory
                                    .getDataSource()
                            },
                        postRestoreHooks =
                            listOf(
                                org.example.project.features.zBon.domain
                                    .KassenSwVersionPostRestoreHook(ConfigurationRepository()),
                            ),
                    ) {
                        error("BuiltInJobsSeeder is only used by updateBackupConfig(), never by the restore path")
                    }

                val zipBytes = backupFile.readBytes()
                val backupFlywayVersion =
                    readFlywayVersionFromBackup(zipBytes)
                        ?: error("backup metadata.json has no flywayVersion — cannot choose the incremental baseline")

                val restoreResult = restoreService.restoreBackup(zipBytes)
                assertTrue(restoreResult.success, "restoreBackup must succeed")
                logger.info(
                    "RealDataMigrationCheck: restored (backup Flyway version = V{})",
                    backupFlywayVersion,
                )

                val ds = DatabaseFactory.getDataSource()
                logFiscalRowCounts(ds, "post-restore") // COUNTS ONLY — no row content
                logFieldLengthOverflows(ds) // round-8 M1/M3 blast radius — COUNTS/lengths ONLY

                // --- 4) Replay the fiscal consolidation V78 over the REAL rows ------------------------
                // A FULL replay from the backup's V$backupFlywayVersion is structurally impossible here
                // (the restore reconstitutes the CURRENT Exposed schema, so the first additive DDL — V77
                // `ADD COLUMN linked_product_id` — hits SQLSTATE 42701 duplicate_column; see class KDoc
                // FINDING). What IS reachable is V78: its DDL is all `IF NOT EXISTS` / idempotent (a no-op
                // against the current schema) and its payload is DML, so baselining at V77 lets Flyway run
                // the real Stammdaten backfill and PLZ repair over the restored rows. V79 is NOT replayed:
                // its plain ADD COLUMN DDL would fail with 42701 against the current schema.
                val maxVersion = highestMigrationVersionOnDisk(ds)
                // Prove the restored schema really is current (a V78 column is present) — so the V78
                // additive DDL below is genuinely re-applied on top of EXISTING columns (proving its
                // IF-NOT-EXISTS idempotence) rather than creating them for the first time.
                assertTrue(
                    columnExists(ds, "zbon", "stamm_kasse_brand"),
                    "restored schema must already carry the V78 stamm_kasse_* columns (Exposed built the " +
                        "current schema); if this is absent the restore/schema assumption has changed",
                )

                // Real-data pre-state for the V78 backfill DECISION (COUNTS/BOOLEANS only — never row data).
                // V78 backfills a closing's Stammdaten snapshot from the live shop_info ONLY when the audit
                // trail is active (>=1 SHOP_INFO row) AND records no SHOP_INFO/UPDATE — the C5/C15
                // precondition. On a V76 restore every zbon.stamm_name is NULL (the column post-dates the
                // backup), so every closing is eligible and the precondition alone decides the outcome.
                val eligibleBefore = scalarLong(ds, "SELECT COUNT(*) FROM \"zbon\" WHERE stamm_name IS NULL")
                val auditTrailActive = scalarBoolean(ds, "SELECT EXISTS(SELECT 1 FROM \"entry_log\" WHERE type = 'SHOP_INFO')")
                val shopEverEdited =
                    scalarBoolean(
                        ds,
                        "SELECT EXISTS(SELECT 1 FROM \"entry_log\" WHERE type = 'SHOP_INFO' AND operation = 'UPDATE')",
                    )
                val v78PreconditionHolds = auditTrailActive && !shopEverEdited
                // V78 only touches shop_info rows whose zip_code is longer than 5 digits (the truncation
                // case). For a German shop this is normally zero, making the PLZ repair a no-op — which we
                // report.
                val zipOver5Digits = scalarLong(ds, "SELECT COUNT(*) FROM \"shop_info\" WHERE length(zip_code::text) > 5")

                val replay =
                    Flyway
                        .configure()
                        .dataSource(ds)
                        .locations(migrationLocation)
                        .baselineOnMigrate(true)
                        .baselineVersion("77") // treat V33..V77 as present (Exposed built the current schema)
                        .baselineDescription("restored real data; replay the fiscal consolidation V78 on top")
                        .target("78")
                        .load()
                        .migrate()
                assertTrue(
                    replay.success,
                    "replaying the fiscal consolidation V78 over the real rows must succeed",
                )
                val executed = replay.migrations.map { it.version }.toSet()
                assertTrue(
                    executed.contains("78"),
                    "V78 must genuinely EXECUTE against the real rows (its IF-NOT-EXISTS DDL is a no-op; " +
                        "its DML runs); executed=$executed",
                )

                // Real-data OUTCOME (structural only). The count is data-dependent, so beyond the hard
                // invariant below it is REPORTED, not asserted — the value is that the real round-7 logic
                // ran on real rows (backup Flyway version = V$backupFlywayVersion).
                val eligibleAfter = scalarLong(ds, "SELECT COUNT(*) FROM \"zbon\" WHERE stamm_name IS NULL")
                val backfilled = eligibleBefore - eligibleAfter
                // Hard invariant that holds on ANY real data: with the precondition failing, V78's UPDATE
                // matches no rows, so nothing is backfilled. (When it holds, backfilled is a subset of the
                // eligible rows — the join to shop_info may exclude orphaned references.)
                if (!v78PreconditionHolds) {
                    assertEquals(
                        eligibleBefore,
                        eligibleAfter,
                        "V78 must backfill NOTHING when its entry_log precondition fails (auditTrailActive=" +
                            "$auditTrailActive, shopEverEdited=$shopEverEdited)",
                    )
                } else {
                    assertTrue(
                        backfilled in 0..eligibleBefore,
                        "V78 backfilled rows must be a subset of the eligible NULL-snapshot closings",
                    )
                }
                logger.info(
                    "RealDataMigrationCheck: replayed V78 over real rows (highest on disk = V{}). " +
                        "V78 backfill — precondition holds={} (auditTrailActive={}, shopEverEdited={}); " +
                        "eligible NULL-snapshot closings before={}, backfilled={}, still NULL after={}.",
                    maxVersion,
                    v78PreconditionHolds,
                    auditTrailActive,
                    shopEverEdited,
                    eligibleBefore,
                    backfilled,
                    eligibleAfter,
                )
                logger.info(
                    "RealDataMigrationCheck: V78 PLZ repair — shop_info rows with a >5-digit zip_code = {} " +
                        "(the repair is a no-op when 0).",
                    zipOver5Digits,
                )

                // --- 5) DSFinV-K export over the real closings — must complete without throwing --------
                val exportService = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())
                val exportBytes =
                    exportService.generateExport(
                        DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2099-12-31"),
                    )
                assertTrue(
                    exportBytes.isNotEmpty(),
                    "DSFinV-K export over the real closings must produce a non-empty ZIP",
                )
                logger.info("RealDataMigrationCheck: DSFinV-K export OK ({} bytes)", exportBytes.size)

                // --- 6) Non-PII diagnostics (counts / sizes / booleans only) ---------------------------
                logExportStructure(exportBytes) // file count + total size + structural invariants
                logR6C1DisclosureCount(ds) // single COUNT for the runbook R6-C1 item

                DatabaseFactory.close()
            } finally {
                // Ephemeral embedded PG is always torn down; nothing is persisted.
                embeddedPg?.close()
            }
        }

    // ------------------------------------------------------------------------------------------
    // Datasource helpers
    // ------------------------------------------------------------------------------------------

    private fun dockerHikariConfig(): HikariConfig =
        HikariConfig().apply {
            jdbcUrl = System.getenv("POS_TEST_PG_URL") ?: "jdbc:postgresql://localhost:5440/pos_db"
            username = System.getenv("POS_TEST_PG_USER") ?: "pos"
            password = System.getenv("POS_TEST_PG_PASSWORD") ?: "pos"
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 4
            isAutoCommit = false
        }

    private fun dockerReachable(config: HikariConfig): Boolean =
        try {
            java.sql.DriverManager
                .getConnection(config.jdbcUrl, config.username, config.password)
                .use { it.isValid(3) }
        } catch (e: Exception) {
            logger.info("RealDataMigrationCheck: Docker PG not reachable at {} ({})", config.jdbcUrl, e.message)
            false
        }

    /** Highest resolved migration version on the classpath, via Flyway's own resolver. */
    private fun highestMigrationVersionOnDisk(ds: DataSource): String =
        Flyway
            .configure()
            .dataSource(ds)
            .locations(migrationLocation)
            .load()
            .info()
            .all()
            .mapNotNull { it.version }
            .maxOrNull()
            ?.version
            ?: error("No migrations found on classpath at $migrationLocation")

    /** Reads `metadata.json`'s `flywayVersion` from the backup zip without touching CSV rows. */
    private fun readFlywayVersionFromBackup(zipBytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "metadata.json") {
                    val meta = Json.decodeFromString(JsonObject.serializer(), zip.readBytes().decodeToString())
                    return (meta["flywayVersion"] as? JsonPrimitive)?.content
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    // ------------------------------------------------------------------------------------------
    // Non-PII diagnostics — everything below emits COUNTS / SIZES / BOOLEANS only, never row data
    // ------------------------------------------------------------------------------------------

    /** Logs COUNT(*) of the fiscally-relevant tables. Counts are not PII. */
    private fun logFiscalRowCounts(
        ds: DataSource,
        phase: String,
    ) {
        val tables = listOf("zbon", "bill", "kassenbuch_header", "kassenbuch_line_item", "tse_transaction")
        ds.connection.use { conn ->
            tables.forEach { t ->
                val n = runCatching { count(conn, "SELECT COUNT(*) FROM \"$t\"") }.getOrElse { -1L }
                logger.info("RealDataMigrationCheck[{}]: rows in {} = {}", phase, t, n)
            }
        }
    }

    private fun count(
        conn: Connection,
        sql: String,
    ): Long =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    /**
     * Round-8 M1/M3 blast-radius probe (COUNTS + max LENGTHS only — never a field VALUE). Reports how
     * many REAL rows would break a DSFinV-K length rule on the next closing/export:
     *  - **M1** — a `terminals` field longer than its `zbon_stamm_terminals` snapshot column (brand/
     *    model/sw_brand `varchar(50)`, serial `varchar(70)`; source is `varchar(100)`). Such a row
     *    aborts `createReport` on the snapshot INSERT, and because the snapshot reads `Terminal.all()`
     *    a SINGLE over-length row (even a deactivated one) blocks EVERY future closing.
     *  - **M3** — a `shop_info` field longer than its index.xml MaxLength (name/strasse 60, ort 62,
     *    stnr 20). It ships past MaxLength in cashpointclosing.csv/location.csv while `canExport=true`.
     * Zero => the finding is LATENT on this backup; non-zero => LIVE, and the fix must precede the next
     * closing/export. Reported, not asserted (data-dependent). (M2 — an edited KASSE_SERIENNR — is NOT
     * settleable from a point-in-time backup: it needs the config-edit audit trail, not a snapshot.)
     */
    private fun logFieldLengthOverflows(ds: DataSource) {
        val termOverflow =
            runCatching {
                scalarLong(
                    ds,
                    "SELECT COUNT(*) FROM \"terminals\" WHERE " +
                        "length(terminal_brand) > 50 OR length(terminal_model) > 50 OR " +
                        "length(terminal_serial_nr) > 70 OR length(terminal_sw_brand) > 50",
                )
            }.getOrElse { -1L }
        val termMax50 =
            runCatching {
                scalarLong(
                    ds,
                    "SELECT COALESCE(MAX(GREATEST(length(terminal_brand), length(terminal_model), " +
                        "length(terminal_sw_brand))), 0) FROM \"terminals\"",
                )
            }.getOrElse { -1L }
        val serialMax =
            runCatching {
                scalarLong(ds, "SELECT COALESCE(MAX(length(terminal_serial_nr)), 0) FROM \"terminals\"")
            }.getOrElse { -1L }
        logger.info(
            "RealDataMigrationCheck[M1]: terminals rows over DSFinV-K snapshot length = {} " +
                "(longest brand/model/sw_brand = {} / cap 50; longest serial = {} / cap 70)",
            termOverflow,
            termMax50,
            serialMax,
        )

        val shopOverflow =
            runCatching {
                scalarLong(
                    ds,
                    "SELECT COUNT(*) FROM \"shop_info\" WHERE " +
                        "length(shop_name) > 60 OR length(shop_address) > 60 OR " +
                        "length(shop_city) > 62 OR length(COALESCE(shop_tax_number, '')) > 20",
                )
            }.getOrElse { -1L }
        logger.info(
            "RealDataMigrationCheck[M3]: shop_info rows over DSFinV-K field length " +
                "(name/strasse 60, ort 62, stnr 20) = {}",
            shopOverflow,
        )
    }

    /** Single-row, single-column `long` query (COUNT/length) — result is a count, never PII. */
    private fun scalarLong(
        ds: DataSource,
        sql: String,
    ): Long = ds.connection.use { conn -> count(conn, sql) }

    /** Single-row `boolean` query (an `EXISTS(...)` precondition probe) — result is a flag, not PII. */
    private fun scalarBoolean(
        ds: DataSource,
        sql: String,
    ): Boolean =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> rs.next() && rs.getBoolean(1) }
            }
        }

    /** True if [table].[column] exists in the public schema — used to prove the restored schema shape. */
    private fun columnExists(
        ds: DataSource,
        table: String,
        column: String,
    ): Boolean =
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT 1 FROM information_schema.columns " +
                        "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                ).use { ps ->
                    ps.setString(1, table)
                    ps.setString(2, column)
                    ps.executeQuery().use { it.next() }
                }
        }

    /**
     * Unzips the export in memory and logs the file COUNT, total byte SIZE, and a handful of
     * data-agnostic structural invariants (pass/fail booleans only — no field values ever logged).
     * A false here is a genuine round-7 finding to record, so this reports rather than asserts.
     */
    private fun logExportStructure(zipBytes: ByteArray) {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                // Key by BASENAME, not the full path. DsfinvkExportBuilder writes entries under the
                // DSFINVK_DIR prefix ("dsfinvk/index.xml", "dsfinvk/lines.csv"), while the bare-name
                // lookups below (entries["index.xml"]) and parseIndexCsvUrls() (bare "<URL>lines.csv")
                // both work in unprefixed names. Keying by the full path made INV-A/B/C always FAIL.
                // Basenames are unique within a DSFinV-K package, so this cannot collide.
                if (!e.isDirectory) entries[e.name.substringAfterLast('/')] = zip.readBytes()
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        val csvNames = entries.keys.filter { it.endsWith(".csv") }.toSet()
        logger.info(
            "RealDataMigrationCheck: export package — {} files total ({} of them .csv), {} bytes",
            entries.size,
            csvNames.size,
            zipBytes.size,
        )

        val indexBytes = entries["index.xml"]
        // INV-A: index.xml present + declares the GDPdU DTD.
        val invGdpdu =
            indexBytes != null &&
                indexBytes.decodeToString().contains("<!DOCTYPE DataSet SYSTEM \"gdpdu-01-09-2004.dtd\">")
        // INV-B: index.xml is well-formed XML (parses without the external DTD).
        val indexUrls: Set<String>? = indexBytes?.let { runCatching { parseIndexCsvUrls(it) }.getOrNull() }
        val invWellFormed = indexUrls != null
        // INV-C: index.xml's referenced CSV set == the ZIP's .csv entries (no dangling/missing file).
        val invFileSet = indexUrls != null && indexUrls == csvNames
        // INV-D: every .csv uses CRLF line endings only (no bare LF).
        val bareLfFiles =
            csvNames.count { name ->
                Regex("(?<!\r)\n").containsMatchIn(entries.getValue(name).decodeToString())
            }
        val invCrlf = bareLfFiles == 0
        // INV-E: every .csv has a non-empty first (header) line.
        val emptyHeaderFiles =
            csvNames.count { name ->
                entries
                    .getValue(name)
                    .decodeToString()
                    .substringBefore("\r\n")
                    .isBlank()
            }
        val invHeader = emptyHeaderFiles == 0

        logger.info("RealDataMigrationCheck: INV-A index.xml GDPdU DTD            = {}", pass(invGdpdu))
        logger.info("RealDataMigrationCheck: INV-B index.xml well-formed XML       = {}", pass(invWellFormed))
        logger.info(
            "RealDataMigrationCheck: INV-C index CSV set == ZIP .csv set    = {} (index={}, zip={})",
            pass(invFileSet),
            indexUrls?.size ?: -1,
            csvNames.size,
        )
        logger.info("RealDataMigrationCheck: INV-D CRLF-only line endings         = {} ({} bad files)", pass(invCrlf), bareLfFiles)
        logger.info("RealDataMigrationCheck: INV-E non-empty header per .csv       = {} ({} bad files)", pass(invHeader), emptyHeaderFiles)
    }

    private fun pass(b: Boolean): String = if (b) "PASS" else "FAIL"

    /** Collects the text of every `<URL>` element in index.xml (CSV file names — not PII). */
    private fun parseIndexCsvUrls(indexBytes: ByteArray): Set<String> {
        val dbf =
            DocumentBuilderFactory.newInstance().apply {
                // Do not fetch the referenced DTD; we only need structure + URL text nodes.
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isNamespaceAware = false
            }
        val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(indexBytes))
        val urls = doc.getElementsByTagName("URL")
        return (0 until urls.length)
            .map { urls.item(it).textContent.trim() }
            .filter { it.endsWith(".csv") }
            .toSet()
    }

    /**
     * R6-C1 disclosure COUNT (runbook `docs/dsfinvk_tse_release_audit.md` §0.3).
     *
     * Counts DISTINCT **TSE-signed** Kassenbuch Vorgänge (headers) that carry at least one line
     * whose resolved GV_TYP is **not rate-governed** (a Privateinlage / Privatentnahme / Geldtransit /
     * Mehrzweckgutschein / TrinkgeldAN / Forderung* / Anfangsbestand / DifferenzSollIst family, per
     * [KassenbuchTaxClassifier.isRateGoverned]) yet still declares `ust_satz` 19 or 7. Those are the
     * Vorgänge that, before R6-C1, signed a false rate container into the immutable WORM.
     *
     * GV_TYP is resolved per line via [KassenbuchDsfinvkMapping.getGvTyp] (entry type + line kind +
     * signed amount + the corrected-entry type for CORRECTION_* rows) — the same authority the
     * exporter/builder use — never from the entry type alone. WORM criterion: `tse_signing_status =
     * 'SIGNED'`. This is a single COUNT — no row data is read out or logged. Best-effort: any failure
     * is logged, not thrown, so it can never redden the core export-survival assertion.
     */
    private fun logR6C1DisclosureCount(ds: DataSource) {
        runCatching {
            ds.connection.use { conn ->
                // header id -> entry type (name string), and id -> corrects-header id, and signed set.
                val entryTypeById = HashMap<Long, KassenbuchEntryType>()
                val correctsById = HashMap<Long, Long>()
                val signedIds = HashSet<Long>()
                var unmappableHeaders = 0
                conn.createStatement().use { st ->
                    st
                        .executeQuery(
                            "SELECT id, entry_type, corrects_header_id, tse_signing_status FROM \"kassenbuch_header\"",
                        ).use { rs ->
                            while (rs.next()) {
                                val id = rs.getLong("id")
                                val et = runCatching { KassenbuchEntryType.valueOf(rs.getString("entry_type")) }.getOrNull()
                                if (et == null) {
                                    unmappableHeaders++
                                    continue
                                }
                                entryTypeById[id] = et
                                val corrects = rs.getLong("corrects_header_id")
                                if (!rs.wasNull()) correctsById[id] = corrects
                                if (rs.getString("tse_signing_status") == "SIGNED") signedIds.add(id)
                            }
                        }
                }

                val offending = HashSet<Long>()
                conn.createStatement().use { st ->
                    st
                        .executeQuery(
                            "SELECT header_id, kind, vorz_betrag, ust_satz FROM \"kassenbuch_line_item\"",
                        ).use { rs ->
                            while (rs.next()) {
                                val headerId = rs.getLong("header_id")
                                if (headerId !in signedIds) continue // WORM criterion
                                val entryType = entryTypeById[headerId] ?: continue
                                if (!isDeclared19or7(rs.getString("ust_satz"))) continue
                                val kind =
                                    runCatching {
                                        KassenbuchLineKind.valueOf(rs.getString("kind"))
                                    }.getOrDefault(KassenbuchLineKind.STANDARD)
                                val vorz = rs.getBigDecimal("vorz_betrag") ?: BigDecimal.ZERO
                                val correctedEntryType = correctsById[headerId]?.let { entryTypeById[it] }
                                val gvTyp = KassenbuchDsfinvkMapping.getGvTyp(entryType, kind, vorz, correctedEntryType)
                                if (!KassenbuchTaxClassifier.isRateGoverned(gvTyp)) offending.add(headerId)
                            }
                        }
                }

                logger.info(
                    "RealDataMigrationCheck: R6-C1 disclosure — TSE-signed Kassenbuch Vorgänge with a " +
                        "nicht-steuerbar GV_TYP line declaring ust_satz 19/7 = {} (signed headers={}, " +
                        "unmappable entry_type headers={})",
                    offending.size,
                    signedIds.size,
                    unmappableHeaders,
                )
            }
        }.onFailure {
            logger.warn("RealDataMigrationCheck: R6-C1 count could not be computed: {}", it.toString())
        }
    }

    /** True when a stored `ust_satz` string denotes 19% or 7% (accepts "19", "19,00", "19.0", "7", …). */
    private fun isDeclared19or7(raw: String?): Boolean {
        val v = raw?.trim()?.replace(',', '.')?.toBigDecimalOrNull() ?: return false
        return v.compareTo(BigDecimal("19")) == 0 || v.compareTo(BigDecimal("7")) == 0
    }

    /**
     * Minimal [IShopConfigurationManager] for the restore path: it only overrides the two methods
     * restore actually calls — [getDocumentStoragePath] (file restore) and [getDefaultBackupPath]
     * (pre-restore safety backup) — routing both to temp dirs so the safety mechanism stays ENABLED
     * rather than being disabled. Every other method delegates to the real object and is never
     * invoked here (Koin is not started; the delegated lazy `inject()`s are never touched).
     */
    private class TempPathConfigManager(
        private val documentStoragePath: String,
        private val backupPath: String,
    ) : IShopConfigurationManager by ShopConfigurationManager {
        override suspend fun getDocumentStoragePath(): String = documentStoragePath

        override suspend fun getDefaultBackupPath(): String? = backupPath
    }
}
