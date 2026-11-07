package org.example.project.database

import org.example.project.testutils.PostgresTestDatabase
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.output.MigrateResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the real Flyway migrations against a real PostgreSQL (zonky embedded PG), closing the
 * round-6 coverage gap #2: the default H2 suite runs [org.example.project.testutils.TestDatabase]
 * with `initDB(runMigrations = false)`, so the actual migration SQL never executes in CI.
 *
 * ## Why this is NOT a from-empty V1..Vmax run
 *
 * The migrations on disk are **V33..V{max}**, and they are incremental `ALTER`/`UPDATE` statements
 * layered on a base schema that **Exposed** creates at install time — no migration `CREATE`s the
 * fiscal base tables (`zbon`, `shop_info`, `entry_log`, `kassenbuch_header`, `table_session`);
 * V33 itself immediately `CREATE`s an index *on* `kassenbuch_header`, assuming it already exists.
 * A Flyway run against a truly empty database therefore fails at the first migration, and
 * `initDB`'s fresh path deliberately **baselines at the latest version and skips every migration**
 * (see `initDB.kt`). Neither exercises the migration SQL.
 *
 * So this test does the faithful equivalent: it hand-materialises exactly the columns the
 * round-5/7 migrations touch, in their **pre-migration shape**, then drives Flyway **directly**
 * (baseline at V77, no fresh-baseline shortcut) so the fiscal consolidation **V78** (formerly
 * V94..V97) genuinely **executes** on top. Each assertion below would FAIL if its migration were
 * reverted (round-6 R13 — no vacuous test). The complementary real-schema, real-data run is
 * [RealDataMigrationCheck] (Docker, local-only), which restores a real backup at Flyway V76 and
 * runs V77..V{max} incrementally.
 *
 * Synthetic seed only — no real backup, no PII in CI. Row contents are never logged.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Flyway migration chain — real PostgreSQL (zonky)")
class MigrationChainTest {
    private val logger = LoggerFactory.getLogger(MigrationChainTest::class.java)
    private val migrationLocation = "classpath:db/migration"

    private lateinit var pg: PostgresTestDatabase
    private lateinit var migrate: MigrateResult

    /** Highest migration version present on disk — computed, never hard-coded (97 drifts). */
    private var maxVersion: String = ""

    @BeforeAll
    fun setUpAndMigrate() {
        pg = PostgresTestDatabase.start()

        maxVersion = highestMigrationVersionOnDisk()
        logger.info("MigrationChainTest: highest migration on disk = V{}", maxVersion)

        pg.dataSource.connection.use { seedPreV78State(it) }

        // Drive Flyway DIRECTLY (not initDB's fresh-baseline shortcut, which skips every
        // migration). baselineOnMigrate + baselineVersion=77 mark V33..V77 as already-present —
        // they are; we hand-built the touched slice — then V78..V{max} genuinely execute.
        migrate =
            Flyway
                .configure()
                .dataSource(pg.dataSource)
                .locations(migrationLocation)
                .baselineOnMigrate(true)
                .baselineVersion("77")
                .baselineDescription("pre-fiscal base (hand-seeded fiscal tables)")
                .target(maxVersion)
                .validateMigrationNaming(true)
                .load()
                .migrate()

        logger.info(
            "MigrationChainTest: Flyway migrate -> success={}, migrationsExecuted={}, target={}, executed={}",
            migrate.success,
            migrate.migrationsExecuted,
            migrate.targetSchemaVersion,
            migrate.migrations.map { "V${it.version}" },
        )
    }

    @AfterAll
    fun tearDown() {
        if (::pg.isInitialized) pg.close()
    }

    // ------------------------------------------------------------------------------------------
    // Genuine-execution proof (the whole point — a skipped chain is a vacuous test)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("V78..Vmax genuinely EXECUTE (not baseline-skipped) and reach the highest on-disk version")
    fun migrationsGenuinelyExecuteToHighestVersion() {
        assertTrue(migrate.success, "Flyway migrate() must succeed")

        val executed = migrate.migrations.map { it.version }.toSet()
        assertTrue(
            executed.contains("78"),
            "Expected the fiscal consolidation V78 to be EXECUTED (not skipped); executed=$executed",
        )
        assertTrue(
            migrate.migrationsExecuted >= 2,
            "At least V78 and V79 must have run; migrationsExecuted=${migrate.migrationsExecuted}",
        )
        // Reached the highest migration on disk (computed, not hard-coded).
        assertEquals(
            maxVersion,
            migrate.targetSchemaVersion,
            "Flyway's applied target must equal the highest migration on disk",
        )
        assertTrue(
            executed.contains(maxVersion),
            "The highest on-disk migration V$maxVersion must itself have executed",
        )

        // Cross-check via info(): V78 is SUCCESS (applied), not BELOW_BASELINE / BASELINE.
        val infos =
            Flyway
                .configure()
                .dataSource(pg.dataSource)
                .locations(migrationLocation)
                .baselineVersion("77")
                .load()
                .info()
                .all()
                .associateBy { it.version?.version }
        assertEquals(
            MigrationState.SUCCESS,
            infos["78"]?.state,
            "V78 must be in state SUCCESS (proves it was executed, not baselined away)",
        )
    }

    // ------------------------------------------------------------------------------------------
    // V78 (fiscal consolidation, formerly V94..V97) — DROP DEFAULT on the three fiscal timestamp columns
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("V78 drops the frozen DDL default on kassenbuch_header/table_session/entry_log")
    fun v78_dropsFrozenTimestampDefaults() {
        // Reverting V78's DROP DEFAULT leaves the literal default in place -> these would be non-null.
        assertNull(
            columnDefault("kassenbuch_header", "created_at"),
            "kassenbuch_header.created_at must have NO DDL default after V78",
        )
        assertNull(
            columnDefault("table_session", "start_time"),
            "table_session.start_time must have NO DDL default after V78",
        )
        assertNull(
            columnDefault("entry_log", "created_at"),
            "entry_log.created_at must have NO DDL default after V78",
        )
    }

    // ------------------------------------------------------------------------------------------
    // V78 (fiscal consolidation) — Stammdaten snapshot backfill, and the >5-digit PLZ repair
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("V82 backfills the Nachsignierung record from the surviving queue rows")
    fun v82_backfillsLateSigningFromQueue() {
        pg.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st
                    .executeQuery(
                        "SELECT entity_type, reference_id, reason_code, reason_text, signed_at, abandoned_at, " +
                            "tse_transaction_id FROM tse_late_signing ORDER BY entity_type",
                    ).use { rs ->
                        assertTrue(rs.next(), "the signed-late BILL queue row must be backfilled")
                        assertEquals("BILL", rs.getString("entity_type"))
                        assertEquals("9001", rs.getString("reference_id"))
                        assertEquals("UNKNOWN_HISTORIC", rs.getString("reason_code"))
                        assertEquals("TSE hardware not found", rs.getString("reason_text"), "last_error is the best evidence left")
                        assertEquals("2026-01-02 12:00:00.0", rs.getTimestamp("signed_at").toString())
                        assertTrue(rs.getObject("tse_transaction_id") != null)

                        assertTrue(rs.next(), "the abandoned KASSENBUCH queue row must be backfilled")
                        assertEquals("KASSENBUCH", rs.getString("entity_type"))
                        assertEquals(
                            "Grund nicht erfasst (vor Version 1.1.0 in die Warteschlange gestellt)",
                            rs.getString("reason_text"),
                            "no last_error -> the stated fallback, never an invented cause",
                        )
                        assertEquals("2026-01-03 09:30:00.0", rs.getTimestamp("abandoned_at").toString())
                    }
            }
        }
    }

    @Test
    @DisplayName("V82 records unsigned TSE transactions as STARTED and signed ones as FINISHED")
    fun v82_marksUnsignedTransactionsStarted() {
        pg.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st
                    .executeQuery(
                        "SELECT process_data_hash, state FROM tse_transaction " +
                            "WHERE process_data_hash IN ('abc', 'def') ORDER BY process_data_hash",
                    ).use { rs ->
                        assertTrue(rs.next())
                        assertEquals("STARTED", rs.getString("state"), "an unsigned row is a start whose finish never landed")
                        assertTrue(rs.next())
                        assertEquals("FINISHED", rs.getString("state"))
                    }
            }
        }
    }

    @Test
    @DisplayName("V82 adds the hex serial without rewriting the raw one, and tolerates a historic duplicate")
    fun v82_addsHexSerial() {
        val hex = "2D2F88FBC30BBC9ACEB75C28E212105996786C7A0A08B2C6CF74306EDC75EC48"
        pg.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st
                    .executeQuery(
                        "SELECT tse_serial_number, tse_serial_hex FROM tse_transaction " +
                            "WHERE transaction_number IN (7, 8) ORDER BY transaction_number, id",
                    ).use { rs ->
                        assertTrue(rs.next())
                        assertEquals(
                            "LS+I+8MLvJrOt1wo4hIQWZZ4bHoKCLLGz3Qwbtx17Eg=",
                            rs.getString(1),
                            "the raw column is never rewritten",
                        )
                        assertEquals(hex, rs.getString(2))
                        assertTrue(rs.next())
                        assertEquals(hex, rs.getString(2), "the same device in the other encoding resolves to one serial")
                        assertTrue(rs.next())
                        assertEquals("U0lNLVRTRS0wMDE=", rs.getString(2), "the simulator's short value is not decoded")
                    }
                st
                    .executeQuery(
                        "SELECT i.indisunique FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid " +
                            "WHERE c.relname = 'idx_tse_tx_serial_hex_tanr'",
                    ).use { rs ->
                        assertTrue(rs.next(), "the index exists")
                        assertEquals(
                            false,
                            rs.getBoolean(1),
                            "a historic duplicate makes it non-unique rather than failing the upgrade",
                        )
                    }
            }
        }
    }

    @Test
    @DisplayName("V78 backfills the Stammdaten snapshot; the PLZ repair preserves a >5-digit PLZ (no truncation)")
    fun v78BackfillAndPlzNotTruncated() {
        pg.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT stamm_name, stamm_plz FROM zbon LIMIT 1").use { rs ->
                    assertTrue(rs.next(), "the seeded zbon closing must exist")

                    // V78's data-migration ran: the snapshot was stamped from shop_info. Reverting
                    // V78's backfill leaves stamm_name absent/NULL -> this fails.
                    assertEquals(
                        "Migration Test Shop",
                        rs.getString("stamm_name"),
                        "V78 backfill must stamp stamm_name (proves the V78 data-migration executed)",
                    )

                    // V78's backfill wrote LPAD(123456,5,'0') = '12345' (truncated); the PLZ repair
                    // inside V78 restores the full value. Reverting the repair leaves '12345';
                    // reverting the backfill leaves it NULL — both fail this assertion.
                    assertEquals(
                        "123456",
                        rs.getString("stamm_plz"),
                        "V78 must PRESERVE the 6-digit PLZ; '12345' means the truncation was not repaired",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // V78 (fiscal consolidation) — Stamm_Kassen snapshot columns + zbon_stamm_terminals table & constraints
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("V78 adds the Stamm_Kassen columns and the zbon_stamm_terminals table with correct NOT NULL / FK")
    fun v78_kassenSnapshotColumnsAndTerminalsTable() {
        for (col in listOf(
            "stamm_kasse_snapshot_at",
            "stamm_kasse_brand",
            "stamm_kasse_modell",
            "stamm_kasse_sw_brand",
            "stamm_kasse_sw_version",
        )) {
            assertTrue(columnExists("zbon", col), "V78 must add column zbon.$col")
        }

        assertTrue(tableExists("zbon_stamm_terminals"), "V78 must create table zbon_stamm_terminals")

        // NOT NULL columns per the migration DDL.
        assertEquals(
            "NO",
            isNullable("zbon_stamm_terminals", "terminal_id"),
            "zbon_stamm_terminals.terminal_id must be NOT NULL",
        )
        assertEquals(
            "NO",
            isNullable("zbon_stamm_terminals", "terminal_sw_brand"),
            "zbon_stamm_terminals.terminal_sw_brand must be NOT NULL",
        )

        // FK zbon_id -> zbon(zbon_id) ON DELETE CASCADE (pg_constraint.confdeltype = 'c').
        assertEquals(
            "c",
            fkDeleteAction("zbon_stamm_terminals"),
            "zbon_stamm_terminals.zbon_id FK must be ON DELETE CASCADE",
        )
    }

    // ------------------------------------------------------------------------------------------
    // V131 — system flag on the virtual location types and the VOUCHER table
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("V131 flags the virtual location types and the VOUCHER table, and nothing else")
    fun v131_flagsSystemRowsOnly() {
        pg.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT id FROM location_types WHERE is_system ORDER BY id").use { rs ->
                    val ids = generateSequence { if (rs.next()) rs.getLong(1) else null }.toList()
                    assertEquals(listOf(4L, 5L), ids, "only Gutschein and Schnellverkauf are system location types")
                }
                st.executeQuery("SELECT table_number, location_type_id FROM r_table WHERE is_system").use { rs ->
                    assertTrue(rs.next(), "the VOUCHER table on the VOUCHER location type must be flagged")
                    assertEquals("VOUCHER", rs.getString(1))
                    assertEquals(4L, rs.getLong(2))
                    assertTrue(!rs.next(), "an ordinary table, or a user table that merely shares the name, is not a system table")
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Seed + query helpers
    // ------------------------------------------------------------------------------------------

    companion object {
        /**
         * Was shared with the migration-squash equivalence harness (retired before the v1.1.0 tag,
         * after the v1.0.10 upgrade rehearsal passed) so both chains it compared started from an
         * identical seed.
         *
         * Materialises the PRE-V78 shape of exactly the tables/columns the consolidated migrations
         * touch. The three timestamp columns carry a LITERAL default (reproducing the frozen
         * `currentTimeStamp()` instant Exposed baked in) so V78's `DROP DEFAULT` has something real to
         * drop; `shop_info.zip_code` is a 6-digit value so the V78 backfill's `LPAD` truncates it and
         * the V78 PLZ repair restores it. `kassenbuch_header.tse_transaction_id`,
         * `tse_transaction.tse_timestamp`/`end_time` and `shop_info.shop_timezone` are present
         * pre-V78 (they exist in the real Exposed schema) so the V78 created_at-realignment UPDATE
         * has join keys, a TSE clock and a timezone to use.
         */
        fun seedPreV78State(conn: Connection) {
            conn.autoCommit = true
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE kassenbuch_header (
                        id                  BIGSERIAL PRIMARY KEY,
                        linked_document_id  BIGINT,
                        tse_transaction_id  BIGINT,
                        -- V84 places every header in its shop's fiscal cash book by joining on this,
                        -- then makes the resulting cash_book_id NOT NULL. A header whose shop_id
                        -- matched nothing would keep a NULL and fail that ALTER, so the seeded row
                        -- below sets it explicitly rather than relying on a default.
                        shop_id             BIGINT NOT NULL,
                        created_at          TIMESTAMP NOT NULL DEFAULT TIMESTAMP '2020-01-01 00:00:00'
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE table_session (
                        id          BIGSERIAL PRIMARY KEY,
                        start_time  TIMESTAMP NOT NULL DEFAULT TIMESTAMP '2020-01-01 00:00:00'
                    )
                    """.trimIndent(),
                )
                // entry_log also carries V78's preconditions (an active audit trail with a SHOP_INFO
                // row, and no SHOP_INFO/UPDATE row) — hence the type/operation columns.
                st.execute(
                    """
                    CREATE TABLE entry_log (
                        id          BIGSERIAL PRIMARY KEY,
                        type        VARCHAR(64),
                        operation   VARCHAR(64),
                        created_at  TIMESTAMP NOT NULL DEFAULT TIMESTAMP '2020-01-01 00:00:00'
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE shop_info (
                        shop_id          BIGSERIAL PRIMARY KEY,
                        shop_name        VARCHAR(255) NOT NULL,
                        shop_address     VARCHAR(500),
                        zip_code         INTEGER NOT NULL,
                        shop_city        VARCHAR(100),
                        shop_country     VARCHAR(100),
                        shop_tax_number  VARCHAR(50),
                        ust_id_nr        VARCHAR(15),
                        shop_timezone    VARCHAR(50) NOT NULL DEFAULT 'Europe/Berlin'
                    )
                    """.trimIndent(),
                )
                // start_time/end_time are V79's join keys when it backfills t_order.zbon_id.
                st.execute(
                    """
                    CREATE TABLE zbon (
                        zbon_id       BIGSERIAL PRIMARY KEY,
                        shop_info_id  BIGINT NOT NULL,
                        start_time    TIMESTAMP,
                        end_time      TIMESTAMP
                    )
                    """.trimIndent(),
                )

                // ---- Tables touched by V79 (formerly V98..V109), in their PRE-migration shape ----
                //
                // Every new migration must either touch an already-seeded table or extend this
                // block. Skipping that is what left the chain red: V98 was added, nothing seeded
                // tse_transaction, and Flyway then failed before reaching anything later.

                // V78 realigns kassenbuch_header.created_at from tse_transaction.tse_timestamp /
                // end_time, so both exist pre-V78 (tse_timestamp = Unix seconds from the TSE
                // hardware, end_time = server-clock finish). V79 widens process_data_hash to TEXT —
                // so it starts narrow, or the widen is vacuous. V79 also adds reference_id/entity_type
                // and log_time_format.
                st.execute(
                    """
                    CREATE TABLE tse_transaction (
                        id                 BIGSERIAL PRIMARY KEY,
                        process_data_hash  VARCHAR(500),
                        tse_timestamp      BIGINT,
                        end_time           TIMESTAMP,
                        signature          VARCHAR(1000),
                        tse_serial_number  VARCHAR(100),
                        transaction_number BIGINT
                    )
                    """.trimIndent(),
                )

                // V82 backfills tse_late_signing from the signing queue, a production table from long
                // before the baseline; only the columns that backfill reads.
                st.execute(
                    """
                    CREATE TABLE tse_signing_queue (
                        id                  BIGSERIAL PRIMARY KEY,
                        entity_type         VARCHAR(20)  NOT NULL,
                        reference_id        VARCHAR(100) NOT NULL,
                        signing_status      VARCHAR(20)  NOT NULL,
                        created_at          TIMESTAMP    NOT NULL,
                        last_attempt_at     TIMESTAMP,
                        signed_at           TIMESTAMP,
                        last_error          TEXT,
                        tse_transaction_id  BIGINT
                    )
                    """.trimIndent(),
                )

                // V79 adds batch_id and backfills it from tse_transaction_id.
                st.execute(
                    """
                    CREATE TABLE cancellation (
                        id                  BIGSERIAL PRIMARY KEY,
                        tse_transaction_id  BIGINT
                    )
                    """.trimIndent(),
                )

                // V79 adds zbon_id (FK to zbon) and backfills it by order_time window.
                st.execute(
                    """
                    CREATE TABLE t_order (
                        id          BIGSERIAL PRIMARY KEY,
                        order_time  TIMESTAMP
                    )
                    """.trimIndent(),
                )

                // V79 strips admin credentials out of the tse_config JSON blob. jsonb, not text —
                // the migration uses the `-` operator and jsonb_typeof.
                st.execute(
                    """
                    CREATE TABLE shop_configuration (
                        id     BIGSERIAL PRIMARY KEY,
                        key    VARCHAR(100) NOT NULL,
                        value  JSONB
                    )
                    """.trimIndent(),
                )

                // V79 alters terminals and creates terminal_pairing_codes; it also renames the
                // TERMINAL employee role. device_id starts NOT NULL so the DROP NOT NULL bites.
                st.execute(
                    """
                    CREATE TABLE terminals (
                        id             BIGSERIAL PRIMARY KEY,
                        terminal_id    VARCHAR(100) NOT NULL,
                        device_id      VARCHAR(100) NOT NULL,
                        registered_at  TIMESTAMP
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE employees (
                        -- employee_id IS the primary key: EmployeeTable is
                        -- UUIDTable("employees", "employee_id"), with no surrogate id column. V85's
                        -- closed_by FK references it, and a plain non-unique column here fails with
                        -- "no unique constraint matching given keys".
                        employee_id      UUID PRIMARY KEY,
                        username         VARCHAR(100) NOT NULL,
                        password_hash    VARCHAR(255) NOT NULL,
                        employee_number  VARCHAR(50),
                        role             VARCHAR(40) NOT NULL,
                        -- V98 replaces the FK on this column with ON DELETE SET NULL, so it must exist
                        -- and must already carry a constraint for the migration's lookup-and-drop to
                        -- have something to find.
                        info_id          INTEGER,
                        created_at       TIMESTAMP NOT NULL,
                        updated_at       TIMESTAMP NOT NULL
                    )
                    """.trimIndent(),
                )

                // V86 creates the e-invoice / bank-statement model, whose two roots carry an
                // ON DELETE SET NULL FK to documents(id). Only the key column matters here.
                // processing_status and uploaded_at carry V89's composite index. Both come from Exposed's
                // DocumentTable, not from any migration, so a bare id column leaves V89 nothing to index.
                st.execute(
                    """
                    CREATE TABLE documents (
                        id                BIGSERIAL PRIMARY KEY,
                        processing_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
                        uploaded_at       TIMESTAMP NOT NULL DEFAULT NOW()
                    )
                    """.trimIndent(),
                )

                // zip_code = 123456 (6 digits): InitialSetupDto admits 3..10 digits, so this is
                // ordinary input, and it is the exact case V78's backfill truncated / its repair fixes.
                st.execute(
                    """
                    INSERT INTO shop_info
                        (shop_name, shop_address, zip_code, shop_city, shop_country, shop_tax_number, ust_id_nr)
                    VALUES
                        ('Migration Test Shop', 'Teststrasse 1', 123456, 'Teststadt', 'Deutschland', '12/345/67890', 'DE123456789')
                    """.trimIndent(),
                )
                // Row inserted WITHOUT created_at, so it inherits the frozen default — evidence the
                // default is live pre-V78 (and would keep fabricating timestamps if V78's DROP DEFAULT
                // were reverted). Linked to the seeded tse_transaction below so V78's created_at
                // realignment has a non-vacuous row to act on.
                st.execute(
                    "INSERT INTO kassenbuch_header (linked_document_id, shop_id) " +
                        "VALUES (NULL, (SELECT shop_id FROM shop_info LIMIT 1))",
                )
                st.execute("INSERT INTO table_session DEFAULT VALUES")
                // V78 precondition: active audit trail (>=1 SHOP_INFO row), no SHOP_INFO/UPDATE row.
                st.execute("INSERT INTO entry_log (type, operation) VALUES ('SHOP_INFO', 'CREATE')")
                // The closing to backfill; stamm_* are absent (added by V78), so stamm_name IS NULL.
                st.execute(
                    """
                    INSERT INTO zbon (shop_info_id, start_time, end_time)
                    SELECT shop_id, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 23:59:59'
                    FROM shop_info LIMIT 1
                    """.trimIndent(),
                )

                // Rows for the V79 DATA migrations, so each has something real to act on.
                // Empty tables would let every UPDATE pass vacuously.
                st.execute(
                    "INSERT INTO tse_transaction (process_data_hash, tse_timestamp, end_time) " +
                        "VALUES ('abc', 1767225600, TIMESTAMP '2026-01-01 00:00:00')",
                )
                // V82 marks unsigned rows STARTED and leaves signed rows FINISHED: seed one of each.
                st.execute(
                    "INSERT INTO tse_transaction (process_data_hash, tse_timestamp, end_time, signature) " +
                        "VALUES ('def', 1767225700, TIMESTAMP '2026-01-01 00:01:00', 'c2ln')",
                )
                // V82: one device written in both encodings at the same TANR (a real duplicate the
                // raw key could not catch), plus the simulator's short Base64, which must stay as it is.
                // The duplicate is DELIBERATE: it pins V82's tolerant branch, where the index is
                // created plain rather than aborting the upgrade. Its unique branch — and the
                // backfill's encoding rules — are covered by TseSerialHexUniqueIndexTest.
                st.execute(
                    "INSERT INTO tse_transaction (tse_serial_number, transaction_number, signature) VALUES " +
                        "('LS+I+8MLvJrOt1wo4hIQWZZ4bHoKCLLGz3Qwbtx17Eg=', 7, 'c2ln'), " +
                        "('2d2f88fbc30bbc9aceb75c28e212105996786c7a0a08b2c6cf74306edc75ec48', 7, 'c2ln'), " +
                        "('U0lNLVRTRS0wMDE=', 8, 'c2ln')",
                )
                // V78's created_at realignment must have a linked row to act on (tse_timestamp set).
                st.execute(
                    "UPDATE kassenbuch_header SET tse_transaction_id = " +
                        "(SELECT id FROM tse_transaction LIMIT 1) WHERE tse_transaction_id IS NULL",
                )
                st.execute(
                    "INSERT INTO cancellation (tse_transaction_id) " +
                        "SELECT id FROM tse_transaction LIMIT 1",
                )
                // V82 backfills tse_late_signing from the queue: one signed-late row and one
                // abandoned row, so both CASE branches of the backfill have something to act on.
                st.execute(
                    "INSERT INTO tse_signing_queue (entity_type, reference_id, signing_status, created_at, " +
                        "last_attempt_at, signed_at, last_error, tse_transaction_id) VALUES " +
                        "('BILL', '9001', 'SIGNED', TIMESTAMP '2026-01-02 10:00:00', NULL, " +
                        "TIMESTAMP '2026-01-02 12:00:00', 'TSE hardware not found', " +
                        "(SELECT id FROM tse_transaction LIMIT 1)), " +
                        "('KASSENBUCH', '17', 'CANCELLED', TIMESTAMP '2026-01-03 09:00:00', " +
                        "TIMESTAMP '2026-01-03 09:30:00', NULL, NULL, NULL)",
                )
                // Inside the seeded zbon window, so V79's zbon_id backfill has a match to find.
                st.execute("INSERT INTO t_order (order_time) VALUES (TIMESTAMP '2026-01-01 12:00:00')")
                // Carries all three secrets V79 must strip.
                st.execute(
                    """
                    INSERT INTO shop_configuration (key, value) VALUES (
                        'tse_config',
                        '{"mode":"LOCAL","adminPin":"12345","adminPuk":"123456","credentialSeed":"seed"}'::jsonb
                    )
                    """.trimIndent(),
                )
                st.execute(
                    "INSERT INTO terminals (terminal_id, device_id, registered_at) " +
                        "VALUES ('T-1', 'dev-1', TIMESTAMP '2026-01-01 08:00:00')",
                )
                // Pre-rename role, so V79's role rename has a row to act on.
                st.execute(
                    """
                    INSERT INTO employees
                        (employee_id, username, password_hash, employee_number, role, created_at, updated_at)
                    VALUES
                        (gen_random_uuid(), 'legacy-terminal', '', 'T-LEGACY', 'TERMINAL', NOW(), NOW())
                    """.trimIndent(),
                )

                // ---- Tables V81..V83 touch, in their POST-V77 shape ----
                //
                // Not part of the fiscal slice above, but on the way: the chain runs to the highest
                // version on disk, so V81, V82 and V83 execute and each needs its table to exist.
                // `choice_group` and the two link tables are created by V77 itself, which
                // `baselineVersion = 77` marks as already applied — so nothing in the run creates
                // them. `item_transfer`, `item_extras`, `product` and `product_extras` come from
                // Exposed's base schema, which no migration creates at all.
                //
                // Shapes are copied from V77 rather than invented: a column narrower than the real
                // one would let a migration pass here against a table production does not have.
                // V98 makes a personnel record deletable and unique per employee. EmployeeInfoTable is
                // IntIdTable("employee_info", "employee_info_id"); the FK it re-creates points here, and
                // its unique index lands on employee_id.
                st.execute(
                    """
                    CREATE TABLE employee_info (
                        employee_info_id SERIAL PRIMARY KEY,
                        employee_id      UUID NOT NULL REFERENCES employees(employee_id)
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    ALTER TABLE employees
                        ADD CONSTRAINT fk_employees_info_id__employee_info_id
                        FOREIGN KEY (info_id) REFERENCES employee_info (employee_info_id)
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE item_transfer (
                        id            BIGSERIAL PRIMARY KEY,
                        transfer_time TIMESTAMP NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE product (
                        id   BIGSERIAL PRIMARY KEY,
                        name VARCHAR(255) NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE product_extras (
                        id        SERIAL PRIMARY KEY,
                        name      VARCHAR(255) NOT NULL,
                        price     DECIMAL(19,4) NOT NULL,
                        is_active BOOLEAN NOT NULL DEFAULT true
                    )
                    """.trimIndent(),
                )
                st.execute("CREATE TABLE order_item (id BIGSERIAL PRIMARY KEY)")
                // ------------------------------------------------------------------------------
                // Tables Exposed creates in production that migrations V99..V104 reach for.
                //
                // Without them the chain aborts at V99 (`relation "customers" does not exist`), and
                // that is how this test came to be dead: the moment the AR work landed, every
                // migration after V99 stopped executing here — V110-V114's permission-group count
                // assertions included — and nothing said so. The failure looked like one broken test
                // rather than a guard that had silently stopped guarding sixteen migrations.
                //
                // Only the columns the migrations actually touch are declared. These stand in for an
                // Exposed-created schema, not for the real one.
                // ------------------------------------------------------------------------------
                st.execute(
                    """
                    CREATE TABLE customers (
                        customer_id UUID PRIMARY KEY,
                        name        VARCHAR(255)
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE bill (
                        id          BIGSERIAL PRIMARY KEY,
                        bill_amount DECIMAL(19,4)
                    )
                    """.trimIndent(),
                )
                st.execute("CREATE TABLE t_order_item (id BIGSERIAL PRIMARY KEY)")
                // V67's bill_promotion, the target of V81's sales_invoice_allowance_charge.bill_promotion_id.
                st.execute("CREATE TABLE bill_promotion (id BIGSERIAL PRIMARY KEY)")
                st.execute(
                    """
                    CREATE TABLE payment_type (
                        id   BIGSERIAL PRIMARY KEY,
                        name VARCHAR(255)
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE payment (
                        id      BIGSERIAL PRIMARY KEY,
                        bill_id BIGINT
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE promotion (
                        id           BIGSERIAL PRIMARY KEY,
                        is_multi_use BOOLEAN NOT NULL DEFAULT false
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE kassenbuch_line_item (
                        id   BIGSERIAL PRIMARY KEY,
                        kind VARCHAR(20)
                    )
                    """.trimIndent(),
                )
                // Deliberately WITHOUT defaults on the two snapshot columns: that is the
                // Exposed-created shape V82 exists to repair, so V82 does real work here instead of
                // re-setting a default V77 already applied.
                st.execute(
                    """
                    CREATE TABLE item_extras (
                        id                   BIGSERIAL PRIMARY KEY,
                        order_item_id        BIGINT NOT NULL REFERENCES order_item(id),
                        product_extras_id    INTEGER REFERENCES product_extras(id),
                        option_name_snapshot VARCHAR(255) NOT NULL,
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
                        min_selections INTEGER NOT NULL DEFAULT 0,
                        max_selections INTEGER NOT NULL DEFAULT 1,
                        is_active      BOOLEAN NOT NULL DEFAULT true,
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
                        sort_position INTEGER NOT NULL DEFAULT 0,
                        is_default    BOOLEAN NOT NULL DEFAULT false,
                        updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (group_id, option_id)
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE product_choice_group (
                        product_id    BIGINT NOT NULL REFERENCES product(id) ON DELETE CASCADE,
                        group_id      INTEGER NOT NULL REFERENCES choice_group(id) ON DELETE CASCADE,
                        sort_position INTEGER NOT NULL DEFAULT 0,
                        updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (product_id, group_id)
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
                // V82 adds `trace_id` to `transaction_audit_log` and indexes it. The table is a
                // production one from long before the baseline, so no migration in this window
                // creates it — per the rule at the top of this block, a new migration must either
                // touch an already-seeded table or extend it. An id-only stub is enough: V82 only
                // ADDs a nullable column.
                st.execute("CREATE TABLE transaction_audit_log (id BIGSERIAL PRIMARY KEY)")
                // V131 flags the seeded virtual location types and the VOUCHER table. Production tables
                // from long before the baseline; only the columns V131 reads are needed, plus one
                // ordinary row of each kind to prove the backfill does not over-reach.
                st.execute("CREATE TABLE location_types (id BIGSERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL)")
                st.execute(
                    "INSERT INTO location_types (id, name) VALUES (1, 'Innenbereich'), (4, 'Gutschein'), " +
                        "(5, 'Schnellverkauf'), (6, 'Lieferdienst')",
                )
                st.execute(
                    "CREATE TABLE r_table (table_id BIGSERIAL PRIMARY KEY, table_number VARCHAR(10) NOT NULL, " +
                        "location_type_id BIGINT NOT NULL REFERENCES location_types (id))",
                )
                st.execute(
                    "INSERT INTO r_table (table_number, location_type_id) VALUES ('VOUCHER', 4), ('T1', 1), ('VOUCHER', 6)",
                )
            }
        }
    }

    /** Highest resolved migration version on the classpath, via Flyway's own resolver. */
    private fun highestMigrationVersionOnDisk(): String =
        Flyway
            .configure()
            .dataSource(pg.dataSource)
            .locations(migrationLocation)
            .load()
            .info()
            .all()
            .mapNotNull { it.version }
            .maxOrNull()
            ?.version
            ?: error("No migrations found on classpath at $migrationLocation")

    private fun columnDefault(
        table: String,
        column: String,
    ): String? =
        pg.dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT column_default FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, table)
                    ps.setString(2, column)
                    ps.executeQuery().use { rs ->
                        require(rs.next()) { "No such column $table.$column" }
                        rs.getString("column_default")
                    }
                }
        }

    private fun isNullable(
        table: String,
        column: String,
    ): String =
        pg.dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, table)
                    ps.setString(2, column)
                    ps.executeQuery().use { rs ->
                        require(rs.next()) { "No such column $table.$column" }
                        rs.getString("is_nullable")
                    }
                }
        }

    private fun columnExists(
        table: String,
        column: String,
    ): Boolean =
        pg.dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, table)
                    ps.setString(2, column)
                    ps.executeQuery().use { it.next() }
                }
        }

    private fun tableExists(table: String): Boolean =
        pg.dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                ).use { ps ->
                    ps.setString(1, table)
                    ps.executeQuery().use { it.next() }
                }
        }

    /** `confdeltype` of the (single) FK on [table]: 'c' = CASCADE, 'a' = NO ACTION, etc. */
    private fun fkDeleteAction(table: String): String? =
        pg.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st
                    .executeQuery(
                        """
                        SELECT confdeltype FROM pg_constraint
                        WHERE conrelid = 'public.$table'::regclass AND contype = 'f'
                        LIMIT 1
                        """.trimIndent(),
                    ).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
}
