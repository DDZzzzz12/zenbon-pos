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
 * The database-level backstop behind the append-only explanation register.
 *
 * An explanation may be corrected ONCE; the correction is then itself corrected, which is what
 * makes a chain A → B → C readable — exactly one entry is current per range.
 * `TseFindingService.requireCorrectable` refuses a second correction of the same entry with a
 * sentence an operator can act on, but it reads and then writes, so two concurrent corrections can
 * both pass it. `tse_explanation_one_correction_per_entry` makes the loser's INSERT fail instead.
 *
 * **PostgreSQL only.** The index is partial, which H2 does not support, and the H2 suite builds its
 * schema from the Exposed objects — so this is the ONLY coverage the constraint has, and a green
 * `:pos-server:test` says nothing about it. Run it with `:pos-server:postgresTest`.
 *
 * The DDL is extracted from the migration itself rather than restated here: a copy would keep
 * passing after the real statement drifted, which is exactly what this guards.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("One correction per explanation — real PostgreSQL (zonky)")
class TseOneCorrectionPerEntryIndexTest {
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
     * The `CREATE UNIQUE INDEX … ;` statement, located by index name across the whole chain —
     * neither the version nor the description of a Flyway file is stable on this branch, and
     * exactly one script may carry it.
     */
    private fun indexDdl(): String {
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
        val sql = carriers.single().second
        val start = sql.indexOf("CREATE UNIQUE INDEX $INDEX_NAME")
        assertTrue(start >= 0, "$INDEX_NAME is no longer created by a CREATE UNIQUE INDEX statement")
        val end = sql.indexOf(';', start)
        assertTrue(end > start, "the CREATE UNIQUE INDEX statement is not terminated")
        val statement = sql.substring(start, end)
        assertTrue(
            "supersedes_id IS NOT NULL" in statement,
            "the index must stay PARTIAL — a plain unique index would also constrain the NULLs: $statement",
        )
        return statement
    }

    /** `tse_explanation` reduced to the columns the index and these inserts touch. */
    private fun seedSchemaAndIndex(c: Connection) {
        c.createStatement().use { s ->
            s.execute("DROP TABLE IF EXISTS tse_explanation")
            s.execute(
                """
                CREATE TABLE tse_explanation (
                    id             BIGSERIAL PRIMARY KEY,
                    tse_serial_hex VARCHAR(100)  NOT NULL,
                    tanr_from      BIGINT        NOT NULL,
                    tanr_to        BIGINT        NOT NULL,
                    text           VARCHAR(2000) NOT NULL,
                    supersedes_id  BIGINT
                )
                """.trimIndent(),
            )
            s.execute(indexDdl())
        }
    }

    /** Inserts one explanation of TANR 3-5 on one device and returns its id. */
    private fun insert(
        c: Connection,
        text: String,
        supersedes: Long?,
    ): Long =
        c.createStatement().use { s ->
            s
                .executeQuery(
                    "INSERT INTO tse_explanation (tse_serial_hex, tanr_from, tanr_to, text, supersedes_id) " +
                        "VALUES ('AA', 3, 5, '$text', ${supersedes?.toString() ?: "NULL"}) RETURNING id",
                ).use {
                    it.next()
                    it.getLong(1)
                }
        }

    private fun rowCount(c: Connection): Int =
        c.createStatement().executeQuery("SELECT COUNT(*) FROM tse_explanation").use {
            it.next()
            it.getInt(1)
        }

    @Test
    fun `a second correction naming the same entry is refused`() {
        pg.dataSource.connection.use { c ->
            seedSchemaAndIndex(c)
            val first = insert(c, "erst", null)
            insert(c, "korrigiert", first)

            val failure = assertFailsWith<SQLException> { insert(c, "nochmal", first) }

            assertTrue(
                INDEX_NAME in failure.message.orEmpty(),
                "the refusal must name the index, so the loser's failure is explainable: ${failure.message}",
            )
            assertEquals(2, rowCount(c), "the register keeps exactly the two entries that were accepted")
        }
    }

    @Test
    fun `correcting the correction is accepted, so a chain stays possible`() {
        pg.dataSource.connection.use { c ->
            seedSchemaAndIndex(c)
            val a = insert(c, "A", null)
            val b = insert(c, "B", a)
            val cId = insert(c, "C", b)

            assertEquals(3, rowCount(c), "A -> B -> C: every link is stored, none is rewritten")
            assertTrue(cId > b && b > a)
        }
    }

    @Test
    fun `original entries, which name nothing, are not constrained against each other`() {
        pg.dataSource.connection.use { c ->
            seedSchemaAndIndex(c)
            // NULL carries no identity: most of the register is first explanations, and on a plain
            // (non-partial) unique index the second of these would already fail on some engines.
            repeat(5) { insert(c, "erste Erläuterung $it", null) }

            assertEquals(5, rowCount(c))
        }
    }

    private companion object {
        const val INDEX_NAME = "tse_explanation_one_correction_per_entry"
    }
}
