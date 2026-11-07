package org.example.project.database

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.features.backup.data.service.BackupRestoreServiceImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.stopKoin
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives the **real restore path** ([BackupRestoreServiceImpl.restoreBackup]) into its abort branch
 * on real PostgreSQL, and proves the aborted restore leaves the database usable.
 *
 * `resetSequences` realigns every sequence with `setval`, which is **not transactional**: on its own
 * a `ROLLBACK` does not undo it. The foreign-key gate is the first abort path that fires *after*
 * that reset, so the worry is a rolled-back database that keeps its original rows while every
 * sequence sits at the *archive's* maximum — every subsequent insert then failing with
 * `duplicate key value violates unique constraint`.
 *
 * The first test drives exactly that scenario end-to-end — a live `cash_book` whose ids run past the
 * archive's single row — and asserts the operator can keep inserting afterwards. **It passes with
 * the realignment removed**, and the second test says why: `executeRestore` runs
 * `TRUNCATE … RESTART IDENTITY` over every table in the same transaction, giving each owned sequence
 * a transaction-local relfilenode that the rollback discards along with the `setval`. So the
 * realignment in `executeRestore`'s catch is a guard for a sequence the TRUNCATE does not cover (and
 * for any future narrowing of it), while these two tests pin both halves of the behaviour so a later
 * change cannot quietly turn the theoretical corruption into a real one.
 *
 * Synthetic data only (a minimal shop + five cash books) — no PII, so this runs in CI alongside
 * [RestoreForeignKeyIntegrityTest].
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Aborted restore realigns sequences — real PostgreSQL (zonky)")
class RestoreAbortSequenceRealignmentTest {
    private lateinit var pg: PostgresTestDatabase

    @BeforeAll
    fun start() {
        pg = PostgresTestDatabase.start()
        stopKoin() // this test drives the service directly; no Koin container
        // Fresh DB: Exposed builds the CURRENT schema and Flyway is baselined at the latest version
        // (never migrated up from scratch). The baseline matters — `createBackup` reads
        // `flyway_schema_history`, and a missing table poisons the backup's transaction.
        initDB(pg.hikariConfig(), runMigrations = true)
    }

    @AfterAll
    fun stop() {
        if (::pg.isInitialized) {
            DatabaseFactory.close()
            pg.close()
        }
    }

    @Test
    @DisplayName("an aborted restore rolls the data back AND leaves every sequence usable")
    fun abortedRestoreRealignsSequences() {
        val docDir = Files.createTempDirectory("posrestore-docs").toFile().apply { deleteOnExit() }
        val safetyDir = Files.createTempDirectory("posrestore-safety").toFile().apply { deleteOnExit() }
        val service =
            BackupRestoreServiceImpl(
                ConfigurationRepository(),
                TempPathConfigManager(docDir.absolutePath, safetyDir.absolutePath),
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
            ) { error("BuiltInJobsSeeder is never used by the backup/restore path") }

        val shopId =
            pg.dataSource.connection.use { conn ->
                insertMinimalRow(conn, "shop_info", idColumn = "shop_id")
            }

        // --- the ARCHIVE: one cash book pointing at a shop that does not exist ---------------------
        // Written with triggers disabled, exactly as the restore's own COPY does — the only way a
        // dangling reference can exist at all. Backing this up produces an archive the foreign-key
        // gate must refuse.
        pg.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("ALTER TABLE cash_book DISABLE TRIGGER ALL")
                st.execute(
                    "INSERT INTO cash_book (shop_id, name, is_fiscal, datev_account, created_at) " +
                        "VALUES (999999, 'Orphan', TRUE, '1000', NOW())",
                )
                st.execute("ALTER TABLE cash_book ENABLE TRIGGER ALL")
            }
        }
        val archive = runBlocking { service.createBackup(null) }

        // --- the LIVE database: the orphan is gone and the ids have run well past the archive ------
        val liveMaxId =
            pg.dataSource.connection.use { conn ->
                conn.createStatement().use { st -> st.execute("DELETE FROM cash_book") }
                repeat(5) {
                    insertMinimalRow(conn, "cash_book", mapOf("shop_id" to shopId.toString(), "is_fiscal" to "FALSE"))
                }
                scalarLong(conn, "SELECT MAX(id) FROM cash_book")
            }
        assertTrue(liveMaxId > 1, "the live ids must run past the archive's single row; got $liveMaxId")

        // --- restoring that archive must abort -----------------------------------------------------
        val failure =
            assertFailsWith<Exception> {
                runBlocking { service.restoreBackup(archive) }
            }
        assertTrue(
            failure.message?.contains("Restore aborted") == true,
            "the restore must fail on the foreign-key gate, not on something else; got: ${failure.message}",
        )

        pg.dataSource.connection.use { conn ->
            // The rollback really did put the operator's rows back.
            assertEquals(5L, scalarLong(conn, "SELECT count(*) FROM cash_book"), "live rows must survive")
            assertEquals(
                0L,
                scalarLong(conn, "SELECT count(*) FROM cash_book WHERE shop_id = 999999"),
                "no archive row may have committed",
            )

            // The invariant: sequences match the data actually in the database. Without the
            // realignment this reads 1 (the archive's maximum), because setval ignored the ROLLBACK.
            assertEquals(
                liveMaxId,
                scalarLong(
                    conn,
                    "SELECT COALESCE(last_value, 0) FROM pg_sequences WHERE schemaname = 'public' " +
                        "AND sequencename = split_part(pg_get_serial_sequence('cash_book', 'id'), '.', 2)",
                ),
                "cash_book's sequence must sit at the live maximum, not the archive's",
            )

            // The user-visible consequence, asserted directly: the next insert must not collide.
            val nextId = insertMinimalRow(conn, "cash_book", mapOf("shop_id" to shopId.toString(), "is_fiscal" to "FALSE"))
            assertEquals(liveMaxId + 1, nextId, "the next insert must draw a fresh id")
        }
    }

    @Test
    @DisplayName("premise: setval survives a ROLLBACK — unless the sequence was RESTART IDENTITY'd first")
    fun setvalIsNotTransactionalUnlessTheSequenceWasRestarted() {
        pg.dataSource.connection.use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS seq_premise")
                st.execute("CREATE TABLE seq_premise (id BIGSERIAL PRIMARY KEY)")
                repeat(3) { st.execute("INSERT INTO seq_premise DEFAULT VALUES") }
            }

            // (a) The documented behaviour: a sequence is non-transactional, so ROLLBACK leaves the
            //     setval in place. This is what makes an aborted restore dangerous in principle.
            conn.autoCommit = false
            conn.createStatement().use { st -> st.execute("SELECT setval('seq_premise_id_seq', 1)") }
            conn.rollback()
            assertEquals(1L, lastValue(conn, "seq_premise_id_seq"), "setval must survive the rollback")

            // (b) …but `executeRestore` runs `TRUNCATE … RESTART IDENTITY` on every table FIRST, in
            //     the same transaction. That gives each owned sequence a transaction-local
            //     relfilenode, so the later setval IS discarded by the rollback along with it. That
            //     is why the abort path already comes back with sane sequences — the realignment in
            //     executeRestore's catch is a belt-and-braces guard for any sequence the TRUNCATE
            //     does not cover, not the thing that saves this path.
            conn.createStatement().use { st -> st.execute("SELECT setval('seq_premise_id_seq', 3)") }
            conn.commit()
            conn.createStatement().use { st ->
                st.execute("TRUNCATE seq_premise RESTART IDENTITY")
                st.execute("SELECT setval('seq_premise_id_seq', 1)")
            }
            conn.rollback()
            assertEquals(
                3L,
                lastValue(conn, "seq_premise_id_seq"),
                "after TRUNCATE … RESTART IDENTITY in the same transaction, the setval rolls back too",
            )
            conn.autoCommit = true
            conn.createStatement().use { st -> st.execute("DROP TABLE seq_premise") }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private fun lastValue(
        conn: Connection,
        sequence: String,
    ): Long = scalarLong(conn, "SELECT COALESCE(last_value, 0) FROM pg_sequences WHERE sequencename = '$sequence'")

    /**
     * Inserts the cheapest row [table] will accept — a type-appropriate placeholder for every
     * NOT-NULL column that has neither a default nor an identity — and returns the generated id.
     *
     * Built from `information_schema` rather than hand-written column lists so it does not rot when
     * a NOT-NULL column is added to `shop_info` or `cash_book`.
     */
    private fun insertMinimalRow(
        conn: Connection,
        table: String,
        overrides: Map<String, String> = emptyMap(),
        idColumn: String = "id",
    ): Long {
        val values = LinkedHashMap<String, String>()
        conn
            .prepareStatement(
                """
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                  AND is_nullable = 'NO' AND column_default IS NULL AND is_identity = 'NO'
                ORDER BY ordinal_position
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val col = rs.getString("column_name")
                        values[col] = overrides[col] ?: placeholderFor(rs.getString("data_type"))
                    }
                }
            }
        overrides.forEach { (col, value) -> values[col] = value }

        val sql =
            "INSERT INTO \"$table\" (${values.keys.joinToString(", ") { "\"$it\"" }}) " +
                "VALUES (${values.values.joinToString(", ")}) RETURNING \"$idColumn\""
        return conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun placeholderFor(dataType: String): String =
        when {
            dataType.contains("char") || dataType == "text" -> "'x'"
            dataType == "boolean" -> "FALSE"
            dataType.startsWith("timestamp") || dataType == "date" -> "NOW()"
            else -> "0"
        }

    private fun scalarLong(
        conn: Connection,
        sql: String,
    ): Long =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    /**
     * Routes the two paths the backup/restore actually touches — document storage and the
     * pre-restore safety backup — at temp dirs, leaving the safety mechanism ENABLED rather than
     * disabling it. Every other member delegates and is never invoked (Koin is not started).
     */
    private class TempPathConfigManager(
        private val documentStoragePath: String,
        private val backupPath: String,
    ) : IShopConfigurationManager by ShopConfigurationManager {
        override suspend fun getDocumentStoragePath(): String = documentStoragePath

        override suspend fun getDefaultBackupPath(): String? = backupPath
    }
}
