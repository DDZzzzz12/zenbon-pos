package org.example.project.core.data.database.secret

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.database.SqlDelightKeyValueStore
import org.example.project.core.data.local.DecimalColumnAdapter
import org.example.project.database.Local_choice_group_options
import org.example.project.database.Local_product_extras
import org.example.project.database.Local_products
import org.example.project.database.PosDatabase
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.example.project.testutils.fakes.FakeSecretCipher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The start-up pass that seals plaintext left by a build before encryption, and wipes old plaintext from the SQLite
 * file once per database — where re-sealing or deleting a row alone leaves it, in freed pages and the WAL, for anyone
 * who copies the file.
 */
class LocalSecretsMigrationTest {
    private val cipher = FakeSecretCipher()
    private val memoryDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { PosDatabase.Schema.create(it) }
    private val rows = SqlDelightKeyValueStore(database(memoryDriver))
    private val store = SecretProtectingKeyValueStore(rows, cipher)

    /** What the migration is given as its driver; the store above talks to [memoryDriver] directly, unrecorded. */
    private val recorder = RecordingDriver(memoryDriver)

    @AfterEach
    fun tearDown() {
        memoryDriver.close()
    }

    private fun TestScope.migration(secrets: SecretProtectingKeyValueStore = store) =
        LocalSecretsMigration(secrets, recorder, StandardTestDispatcher(testScheduler))

    @Test
    fun `a run that sealed a plaintext secret wipes the freed plaintext`() =
        runTest {
            rows.setString("device_token", "tok-plain")

            migration().run()

            assertTrue(SealedSecret.isSealed(rows.getString("device_token")!!))
            // An in-memory database answers "memory" to journal_mode, so there is no WAL to checkpoint.
            assertEquals(WIPE_WITHOUT_WAL, recorder.statements)
            assertEquals(WIPE_DONE, rows.getString(WIPE_MARKER))
        }

    /**
     * The launch gate or the device-token loader can read a secret before the migration runs, and a read re-seals legacy
     * plaintext on its own. The migration then finds nothing to seal — and used to skip the wipe, leaving the plaintext
     * the read freed in the file for good.
     */
    @Test
    fun `a legacy secret a read already re-sealed before the run still gets wiped`() =
        runTest {
            rows.setString("device_token", "tok-plain")
            assertEquals("tok-plain", store.getString("device_token"))
            assertTrue(SealedSecret.isSealed(rows.getString("device_token")!!), "precondition: the read re-sealed the row")

            migration().run()

            assertEquals(WIPE_WITHOUT_WAL, recorder.statements, "nothing was left to seal, but the read freed the plaintext")
            assertEquals(WIPE_DONE, rows.getString(WIPE_MARKER))
        }

    /** No plaintext row does not mean no plaintext: freed pages can still hold deleted JWTs and earlier passwords. */
    @Test
    fun `a database with no plaintext and no marker is vacuumed once and marked`() =
        runTest {
            store.setString("device_token", "tok-1")
            rows.setString("api_base_url", "https://kasse.example.de/")

            migration().run()

            assertEquals(WIPE_WITHOUT_WAL, recorder.statements)
            assertEquals(WIPE_DONE, rows.getString(WIPE_MARKER))
        }

    @Test
    fun `once the marker is set, a start with nothing to seal leaves the database file alone`() =
        runTest {
            rows.setString(WIPE_MARKER, WIPE_DONE)
            store.setString("device_token", "tok-1")

            migration().run()

            assertEquals(emptyList(), recorder.statements)
            assertEquals(WIPE_DONE, rows.getString(WIPE_MARKER))
        }

    /** Plaintext after the marker is set — a downgrade to a build before encryption, then back — is still wiped. */
    @Test
    fun `once the marker is set, a start that seals plaintext still wipes`() =
        runTest {
            rows.setString(WIPE_MARKER, WIPE_DONE)
            rows.setString("password", "pw-plain")

            migration().run()

            assertTrue(SealedSecret.isSealed(rows.getString("password")!!))
            assertEquals(WIPE_WITHOUT_WAL, recorder.statements)
            assertEquals(WIPE_DONE, rows.getString(WIPE_MARKER))
        }

    /**
     * The marker comes off before the seal and goes back on after the `VACUUM`: a crash, or a `VACUUM` that fails, between
     * the two must leave the wipe owed.
     */
    @Test
    fun `plaintext found under a set marker clears it before sealing, and it is set again only after the VACUUM`() =
        runTest {
            val events = mutableListOf<String>()
            val watched =
                object : KeyValueStore by rows {
                    override fun setString(
                        key: String,
                        value: String,
                    ) {
                        events += "set $key" + if ("VACUUM" in recorder.statements) " after VACUUM" else ""
                        rows.setString(key, value)
                    }

                    override fun remove(key: String) {
                        events += "remove $key"
                        rows.remove(key)
                    }
                }
            rows.setString(WIPE_MARKER, WIPE_DONE)
            rows.setString("device_token", "tok-plain")

            migration(SecretProtectingKeyValueStore(watched, cipher)).run()

            assertEquals(listOf("remove $WIPE_MARKER", "set device_token", "set $WIPE_MARKER after VACUUM"), events)
        }

    @Test
    fun `a VACUUM that failed leaves the marker unset, and the next start vacuums`() =
        runTest {
            rows.setString("device_token", "tok-plain")
            recorder.failingStatements += "VACUUM"

            migration().run()

            assertNull(rows.getString(WIPE_MARKER), "the wipe did not happen, so it must stay owed")
            recorder.failingStatements.clear()
            recorder.statements.clear()

            // A new instance, as on the next start, over the same database.
            migration().run()

            assertEquals(WIPE_WITHOUT_WAL, recorder.statements, "nothing was sealed this time, but the plaintext is still in the file")
            assertEquals(WIPE_DONE, rows.getString(WIPE_MARKER))
        }

    /**
     * A seal that fails at boot leaves the plaintext live in its row, where no `VACUUM` can reach it. A later read that
     * re-seals it frees that plaintext — so the start that could not seal must not record the wipe as done.
     */
    @Test
    fun `a seal that failed leaves the marker unset, so a later read's re-seal is still wiped`() =
        runTest {
            rows.setString("password", "pw-plain")
            cipher.canSeal = false

            migration().run()

            assertEquals(WIPE_WITHOUT_WAL, recorder.statements, "older freed pages are wiped even so")
            assertNull(rows.getString(WIPE_MARKER), "a row still holds plaintext, so the wipe is not done")

            cipher.canSeal = true
            assertEquals("pw-plain", store.getString("password"))
            assertTrue(SealedSecret.isSealed(rows.getString("password")!!), "precondition: the read re-sealed the row")
            recorder.statements.clear()

            migration().run()

            assertEquals(WIPE_WITHOUT_WAL, recorder.statements)
            assertEquals(WIPE_DONE, rows.getString(WIPE_MARKER))
        }

    @Test
    fun `a VACUUM that succeeded sets the marker, even when the WAL step fails`() =
        runTest {
            rows.setString("password", "pw-plain")
            recorder.failingStatements += "PRAGMA journal_mode"

            migration().run()

            assertEquals(WIPE_WITHOUT_WAL, recorder.statements)
            assertEquals(WIPE_DONE, rows.getString(WIPE_MARKER), "the checkpoint is best-effort after a VACUUM that worked; it must not keep the wipe owed")
        }

    @Test
    fun `a failing driver does not fail start-up and every wipe step is still tried`() =
        runTest {
            rows.setString("password", "pw-plain")
            recorder.failEverything = true

            migration().run()

            assertEquals(WIPE_WITHOUT_WAL, recorder.statements)
            assertTrue(SealedSecret.isSealed(rows.getString("password")!!), "the seal itself does not go through the wipe")
            assertNull(rows.getString(WIPE_MARKER))
        }

    @Test
    fun `storage that cannot be read does not fail start-up`() =
        runTest {
            val unreadable =
                object : KeyValueStore by FakeKeyValueStore() {
                    override fun getString(key: String): String? = throw IllegalStateException("disk I/O error")
                }

            migration(SecretProtectingKeyValueStore(unreadable, cipher)).run()

            assertEquals(emptyList(), recorder.statements, "the marker cannot be read, so the wipe waits for the next start")
        }

    @Test
    fun `it runs once per process`() =
        runTest {
            val migration = migration()
            migration.run()
            recorder.statements.clear()
            rows.setString("device_token", "tok-plain")

            migration.run()

            assertEquals("tok-plain", rows.getString("device_token"), "a second run must not seal again")
            assertEquals(emptyList(), recorder.statements)
        }

    @Test
    fun `the plaintext leaves a rollback-journal database file`(
        @TempDir dir: Path,
    ) = runTest {
        assertPlaintextWipedFromFile(dir, walMode = false)
    }

    @Test
    fun `the plaintext leaves a WAL-mode database file and its WAL`(
        @TempDir dir: Path,
    ) = runTest {
        assertPlaintextWipedFromFile(dir, walMode = true)
    }

    /**
     * Upgrades a real SQLite file that holds a plaintext device token, lets a read re-seal it before the migration runs —
     * as the launch gate or the device-token loader can — runs the migration over it, and checks that no file SQLite left
     * behind still holds the plaintext bytes.
     */
    private suspend fun TestScope.assertPlaintextWipedFromFile(
        dir: Path,
        walMode: Boolean,
    ) {
        val file = dir.resolve("pos_database.db").toFile()
        val fileDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            if (walMode) fileDriver.executeQuery(null, "PRAGMA journal_mode=WAL", { QueryResult.Value(it.next().value) }, 0)
            PosDatabase.Schema.create(fileDriver)
            val fileRows = SqlDelightKeyValueStore(database(fileDriver))
            fileRows.setString("device_token", PLAINTEXT)
            // Rows written after the token, as on any till that has been in use. Without them the token's cell is the
            // newest in its page, and SQLite writes the larger sealed row straight over its bytes — the wipe would then
            // have nothing left to prove.
            listOf("api_base_url", "userData", "language").forEach { fileRows.setString(it, "value-of-$it") }
            val secrets = SecretProtectingKeyValueStore(fileRows, cipher)

            assertEquals(PLAINTEXT, secrets.getString("device_token"), "an early read, before the migration")
            assertTrue(SealedSecret.isSealed(fileRows.getString("device_token")!!), "precondition: the read re-sealed the row")
            assertTrue(filesOf(file).any { it.holdsPlaintext() }, "precondition: the re-seal left the plaintext on disk")

            LocalSecretsMigration(secrets, fileDriver, StandardTestDispatcher(testScheduler)).run()

            assertEquals(WIPE_DONE, fileRows.getString(WIPE_MARKER), "a real VACUUM succeeded, so the wipe is recorded")
        } finally {
            fileDriver.close()
        }
        filesOf(file).forEach { assertFalse(it.holdsPlaintext(), "${it.name} still holds the plaintext device token") }
    }

    private fun filesOf(database: File): List<File> = listOf(database, File("${database.path}-wal"), File("${database.path}-journal")).filter { it.exists() }

    private fun File.holdsPlaintext(): Boolean = String(readBytes(), Charsets.ISO_8859_1).contains(PLAINTEXT)

    private fun database(driver: SqlDriver): PosDatabase =
        PosDatabase(
            driver,
            local_choice_group_optionsAdapter = Local_choice_group_options.Adapter(price_deltaAdapter = DecimalColumnAdapter),
            local_productsAdapter = Local_products.Adapter(priceAdapter = DecimalColumnAdapter),
            local_product_extrasAdapter = Local_product_extras.Adapter(priceAdapter = DecimalColumnAdapter),
        )

    /** Records the statements the migration sends, and can make every one of them, or chosen ones, fail. */
    private class RecordingDriver(
        private val real: SqlDriver,
    ) : SqlDriver by real {
        val statements = mutableListOf<String>()
        var failEverything = false
        val failingStatements = mutableSetOf<String>()

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            record(sql)
            return real.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            record(sql)
            return real.execute(identifier, sql, parameters, binders)
        }

        private fun record(sql: String) {
            statements += sql
            if (failEverything || sql in failingStatements) throw IllegalStateException("database is locked")
        }
    }

    private companion object {
        /** Spelled out rather than read from the migration: a persisted key renamed in code would orphan every marker on disk. */
        const val WIPE_MARKER = "local_secrets_wipe_v1"

        const val WIPE_DONE = "done"

        /** Distinctive, and not contained in its own sealed (reversed) form. */
        const val PLAINTEXT = "plain-device-token-5f1c7a"

        val WIPE_WITHOUT_WAL = listOf("PRAGMA secure_delete=ON", "VACUUM", "PRAGMA journal_mode")
    }
}
