package org.example.project.database

import org.example.project.testutils.PostgresTestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V82's canonical-serial column and the two branches of its index.
 *
 * TR-03153-1 §9.3.2 allows only the hex string for identifying and assigning a TSE, so a row
 * holding the device's Base64 serial names the same device under a second, invalid identity — and
 * the old `(tse_serial_number, transaction_number)` key, comparing raw strings, could not see that
 * the two were one device at one TANR. `tse_serial_hex` is that identity, and
 * `idx_tse_tx_serial_hex_tanr` is UNIQUE unless history already holds such a duplicate, in which
 * case it is created PLAIN — aborting the upgrade there would strand exactly the installs this
 * work exists for. The tolerant branch is covered by `MigrationChainTest.v82_addsHexSerial`;
 * this covers the unique one, plus the backfill's encoding rules.
 *
 * **PostgreSQL only.** The migration uses `encode`/`decode` and a `DO` block, and the H2 suite
 * builds its schema from the Exposed objects without running Flyway — so a green
 * `:pos-server:test` says nothing about any of this. Run it with `:pos-server:postgresTest`.
 *
 * The SQL is read from the migration itself rather than restated here: a copy would keep passing
 * after the real script drifted, which is exactly what this guards.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("The canonical hex TSE serial — real PostgreSQL (zonky)")
class TseSerialHexUniqueIndexTest {
    private lateinit var pg: PostgresTestDatabase

    @BeforeAll
    fun start() {
        pg = PostgresTestDatabase.start()
    }

    @AfterAll
    fun stop() {
        if (::pg.isInitialized) pg.close()
    }

    /**
     * The migration that creates the index, located by the index name across the whole chain —
     * neither the version nor the description of a Flyway file is stable on this branch, and
     * exactly one script may carry it.
     */
    private fun migrationSql(): String {
        val dir =
            checkNotNull(javaClass.classLoader.getResource("db/migration")) { "db/migration not on the test classpath" }
        val scripts =
            checkNotNull(File(dir.toURI()).listFiles { f: File -> f.name.endsWith(".sql") }) {
                "db/migration is not a readable directory"
            }
        val carriers = scripts.sortedBy { it.name }.map { it to it.readText() }.filter { (_, sql) -> INDEX_NAME in sql }
        assertEquals(
            1,
            carriers.size,
            "exactly one migration must create $INDEX_NAME; found ${carriers.map { it.first.name }}",
        )
        return hexSerialSection(carriers.single().second)
    }

    /**
     * The hex-serial statements alone — column, backfill and index block — cut out of their carrier.
     * The carrier is a grouped schema file (the pre-release fold put these in the cash-and-fiscal
     * group), whose other statements need tables this test does not build.
     */
    private fun hexSerialSection(sql: String): String {
        val start = sql.indexOf(COLUMN_DDL)
        assertTrue(start >= 0, "the carrier no longer adds $COLUMN_DDL")
        val indexAt = sql.indexOf(INDEX_NAME, start)
        assertTrue(indexAt > start, "$INDEX_NAME is not created after the hex column")
        val end = sql.indexOf("END $$;", indexAt)
        assertTrue(end > indexAt, "the index DO-block has no END terminator")
        return sql.substring(start, end + "END $$;".length)
    }

    /** `tse_transaction` reduced to the columns V82 reads — pre-migration, so no hex column. */
    private fun seedSchema(c: Connection) {
        c.createStatement().use { s ->
            s.execute("DROP TABLE IF EXISTS tse_transaction")
            s.execute(
                """
                CREATE TABLE tse_transaction (
                    id                 BIGSERIAL PRIMARY KEY,
                    tse_serial_number  VARCHAR(100),
                    transaction_number BIGINT
                )
                """.trimIndent(),
            )
        }
    }

    private fun insert(
        c: Connection,
        serial: String?,
        tanr: Long?,
    ) = c.createStatement().use { s ->
        s.execute(
            "INSERT INTO tse_transaction (tse_serial_number, transaction_number) VALUES (" +
                (serial?.let { "'$it'" } ?: "NULL") + ", " + (tanr?.toString() ?: "NULL") + ")",
        )
    }

    private fun migrate(c: Connection) = c.createStatement().use { it.execute(migrationSql()) }

    /**
     * Whether PostgreSQL considers the index unique. Fails rather than returning null when the
     * index is absent — a missing index is a failure of the migration, not an outcome to compare
     * against. Joined through `pg_class` by name, so a missing index is a missing row here and
     * not a thrown `::regclass` cast.
     */
    private fun indexUniqueness(c: Connection): Boolean =
        c
            .createStatement()
            .executeQuery(
                "SELECT i.indisunique FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid " +
                    "WHERE c.relname = '$INDEX_NAME'",
            ).use {
                assertTrue(it.next(), "the migration must create $INDEX_NAME")
                it.getBoolean(1)
            }

    private fun hexOf(
        c: Connection,
        tanr: Long,
    ): String? =
        c
            .createStatement()
            .executeQuery("SELECT tse_serial_hex FROM tse_transaction WHERE transaction_number = $tanr")
            .use { if (it.next()) it.getString(1) else null }

    @Test
    fun `without a duplicate the index is unique`() {
        pg.dataSource.connection.use { c ->
            seedSchema(c)
            // One device, two TANRs — the normal shape. Nothing to tolerate.
            insert(c, BASE64_SERIAL, 1)
            insert(c, BASE64_SERIAL, 2)

            migrate(c)

            assertEquals(true, indexUniqueness(c), "with nothing to tolerate, the key must be a real constraint")
        }
    }

    @Test
    fun `the unique index catches the cross-encoding duplicate the raw key could not`() {
        pg.dataSource.connection.use { c ->
            seedSchema(c)
            insert(c, BASE64_SERIAL, 1)
            migrate(c)

            // The same device at the same TANR, written the other way round. The raw-string key
            // saw two different serials; the canonical one sees one device signing TANR 1 twice.
            val failure =
                assertFailsWith<SQLException> {
                    c.createStatement().use {
                        it.execute(
                            "INSERT INTO tse_transaction (tse_serial_number, transaction_number, tse_serial_hex) " +
                                "VALUES ('$HEX_SERIAL', 1, '$HEX_SERIAL')",
                        )
                    }
                }
            assertTrue(
                INDEX_NAME in failure.message.orEmpty(),
                "the refusal must name the index so it is explainable: ${failure.message}",
            )
        }
    }

    @Test
    fun `the backfill decodes a 32-byte Base64 serial, uppercases hex, and leaves everything else alone`() {
        pg.dataSource.connection.use { c ->
            seedSchema(c)
            insert(c, BASE64_SERIAL, 1)
            insert(c, HEX_SERIAL.lowercase(), 2)
            // Hex for "SIM-TSE-001". It is ALSO decodable as Base64, so a second decode would
            // invent a serial — only a full 43-char + '=' value may be decoded.
            insert(c, "53494D2D5453452D303031", 3)
            insert(c, "SIM-TSE-001", 4)
            insert(c, null, 5)

            migrate(c)

            assertEquals(HEX_SERIAL, hexOf(c, 1))
            assertEquals(HEX_SERIAL, hexOf(c, 2))
            assertEquals("53494D2D5453452D303031", hexOf(c, 3))
            assertEquals("SIM-TSE-001", hexOf(c, 4))
            assertEquals(null, hexOf(c, 5), "a row that names no device gains no invented one")
        }
    }

    @Test
    fun `the raw column is never rewritten`() {
        pg.dataSource.connection.use { c ->
            seedSchema(c)
            insert(c, BASE64_SERIAL, 1)
            // Not only the decoded case: the lowercase-hex row is uppercased in the new column,
            // and that must not leak back into the old one either.
            insert(c, HEX_SERIAL.lowercase(), 2)

            migrate(c)

            assertEquals(BASE64_SERIAL, rawOf(c, 1), "history keeps the value it was written with (owner's decision)")
            assertEquals(HEX_SERIAL.lowercase(), rawOf(c, 2), "not even a change of case is written back")
            assertEquals(HEX_SERIAL, hexOf(c, 2), "while the canonical column is uppercase")
        }
    }

    private fun rawOf(
        c: Connection,
        tanr: Long,
    ): String? =
        c
            .createStatement()
            .executeQuery("SELECT tse_serial_number FROM tse_transaction WHERE transaction_number = $tanr")
            .use { if (it.next()) it.getString(1) else null }

    private companion object {
        const val INDEX_NAME = "idx_tse_tx_serial_hex_tanr"
        const val COLUMN_DDL = "ALTER TABLE tse_transaction ADD COLUMN tse_serial_hex"
        const val BASE64_SERIAL = "LS+I+8MLvJrOt1wo4hIQWZZ4bHoKCLLGz3Qwbtx17Eg="
        const val HEX_SERIAL = "2D2F88FBC30BBC9ACEB75C28E212105996786C7A0A08B2C6CF74306EDC75EC48"
    }
}
