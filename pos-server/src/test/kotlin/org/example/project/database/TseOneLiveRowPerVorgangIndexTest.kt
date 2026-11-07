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
 * The database-level backstop against signing one Vorgang twice — and its tolerant fallback.
 *
 * Nothing locks a Vorgang while it is signed, so the retry sweeper and a manual retry (or the
 * online path racing the queue) can both find no current row and both sign — two signatures over
 * the same payload in write-once memory. `tse_transaction_one_live_per_vorgang` makes the loser's
 * FIRST commit fail instead; `TseVorgangSigner.persisting` turns that into TSE_SIGNING_FAILED and
 * the Vorgang is queued, so the retry adopts the winner's row.
 *
 * V82 creates it UNIQUE only when the database is clean. An install that ALREADY double-signed is
 * exactly the install this work is for, and a failed `CREATE UNIQUE INDEX` would abort its upgrade
 * with no later migration able to repair it — so there the same index is created PLAIN with a
 * NOTICE, and the duplicates are explained afterwards. BOTH branches are proven below.
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
@DisplayName("One live tse_transaction per Vorgang — real PostgreSQL (zonky)")
class TseOneLiveRowPerVorgangIndexTest {
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
     * The `DO $$ … END $$;` block that creates the index, located by the index name across the
     * whole chain — neither the version nor the description of a Flyway file is stable on this
     * branch, and exactly one script may carry it.
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
        val blocks = sql.split("DO $$").drop(1).map { "DO $$" + it }
        val block =
            blocks.firstOrNull { INDEX_NAME in it }
                ?: error("$INDEX_NAME is not created inside a DO-block — the tolerant fallback is gone")
        val end = block.indexOf("END $$;")
        assertTrue(end > 0, "the DO-block has no END terminator")
        return block.substring(0, end + "END $$;".length)
    }

    /** `tse_transaction` reduced to the columns the index and these inserts touch — no DDL yet. */
    private fun seedSchema(c: Connection) {
        c.createStatement().use { s ->
            s.execute("DROP TABLE IF EXISTS tse_transaction")
            s.execute(
                """
                CREATE TABLE tse_transaction (
                    id            BIGSERIAL PRIMARY KEY,
                    reference_id  VARCHAR(100),
                    entity_type   VARCHAR(30),
                    signature     VARCHAR(1000),
                    state         VARCHAR(16) NOT NULL DEFAULT 'FINISHED'
                )
                """.trimIndent(),
            )
        }
    }

    /** The schema plus the migration's index block, on a database with nothing in it yet. */
    private fun seedSchemaAndIndex(c: Connection) {
        seedSchema(c)
        c.createStatement().use { it.execute(indexDdl()) }
    }

    private fun insert(
        c: Connection,
        reference: String?,
        entityType: String?,
        state: String,
    ) = c.createStatement().use { s ->
        s.execute(
            "INSERT INTO tse_transaction (reference_id, entity_type, state) VALUES (" +
                (reference?.let { "'$it'" } ?: "NULL") + ", " +
                (entityType?.let { "'$it'" } ?: "NULL") + ", '$state')",
        )
    }

    private fun liveRows(c: Connection): Int = count(c, "SELECT COUNT(*) FROM tse_transaction WHERE state <> 'SUPERSEDED'")

    private fun count(
        c: Connection,
        sql: String,
    ): Int =
        c.createStatement().executeQuery(sql).use {
            it.next()
            it.getInt(1)
        }

    /** Whether the index exists, and whether PostgreSQL considers it unique. */
    private fun indexUniqueness(c: Connection): Boolean? =
        c
            .createStatement()
            .executeQuery("SELECT indisunique FROM pg_index WHERE indexrelid = '$INDEX_NAME'::regclass")
            .use { if (it.next()) it.getBoolean(1) else null }

    @Test
    fun `on a clean database the index is created UNIQUE`() {
        pg.dataSource.connection.use { c ->
            seedSchema(c)
            insert(c, "79", "BILL", "FINISHED")
            insert(c, "80", "BILL", "FINISHED")

            c.createStatement().use { it.execute(indexDdl()) }

            assertEquals(true, indexUniqueness(c), "with nothing to tolerate, the backstop must be a real constraint")
        }
    }

    @Test
    fun `a second live row for the same Vorgang is refused`() {
        pg.dataSource.connection.use { c ->
            seedSchemaAndIndex(c)
            insert(c, "79", "BILL", "STARTED")

            val failure = assertFailsWith<SQLException> { insert(c, "79", "BILL", "STARTED") }

            assertTrue(
                INDEX_NAME in failure.message.orEmpty(),
                "the refusal must name the index, so the loser's failure is explainable: ${failure.message}",
            )
            assertEquals(1, liveRows(c), "exactly one worker may own the Vorgang")
        }
    }

    @Test
    fun `a replacement is allowed once the first row is SUPERSEDED`() {
        pg.dataSource.connection.use { c ->
            seedSchemaAndIndex(c)
            insert(c, "79", "BILL", "SUPERSEDED")
            insert(c, "79", "BILL", "SUPERSEDED")

            // The Vorgang is signed again on a new TANR — the normal replacement path.
            insert(c, "79", "BILL", "FINISHED")

            assertEquals(1, liveRows(c))
            assertEquals(
                3,
                count(c, "SELECT COUNT(*) FROM tse_transaction"),
                "every replaced TANR stays on file: it is exported as an extra, unsigned row",
            )
        }
    }

    @Test
    fun `rows with no Vorgang link are not constrained against each other`() {
        pg.dataSource.connection.use { c ->
            seedSchemaAndIndex(c)
            // Pre-M7 history: no reference at all. NULLs carry no identity, so these must coexist.
            insert(c, null, null, "FINISHED")
            insert(c, null, null, "FINISHED")
            insert(c, "79", null, "FINISHED")
            insert(c, "79", null, "FINISHED")

            assertEquals(4, liveRows(c))
        }
    }

    @Test
    fun `different Vorgänge, and the same id under different kinds, coexist`() {
        pg.dataSource.connection.use { c ->
            seedSchemaAndIndex(c)
            insert(c, "79", "BILL", "FINISHED")
            insert(c, "80", "BILL", "FINISHED")
            // A bill id and an order id are separate namespaces; only the PAIR identifies a Vorgang.
            insert(c, "79", "ORDER", "FINISHED")

            assertEquals(3, liveRows(c))
        }
    }

    @Test
    fun `an install that already double-signed still upgrades, with a plain index instead`() {
        pg.dataSource.connection.use { c ->
            seedSchema(c)
            // The historical defect: one Vorgang, two live signed rows.
            insert(c, "79", "BILL", "FINISHED")
            insert(c, "79", "BILL", "FINISHED")
            insert(c, "80", "BILL", "FINISHED")

            // Must NOT throw — aborting here would halt the whole chain on exactly the installs
            // this work exists for, and no later migration could repair it.
            c.createStatement().use { it.execute(indexDdl()) }

            assertEquals(false, indexUniqueness(c), "the index must exist, but non-unique, or the upgrade would have failed")
            assertEquals(3, liveRows(c), "the migration must not delete or rewrite fiscal rows to make itself fit")

            // And it stays tolerant: the duplicates are explained by hand afterwards, not here.
            insert(c, "80", "BILL", "FINISHED")
            assertEquals(4, liveRows(c))
        }
    }

    @Test
    fun `duplicates that are all SUPERSEDED do not spoil the unique branch`() {
        pg.dataSource.connection.use { c ->
            seedSchema(c)
            // A Vorgang with a long supersede history is NORMAL, not a double-sign.
            insert(c, "79", "BILL", "SUPERSEDED")
            insert(c, "79", "BILL", "SUPERSEDED")
            insert(c, "79", "BILL", "FINISHED")

            c.createStatement().use { it.execute(indexDdl()) }

            assertEquals(true, indexUniqueness(c))
        }
    }

    private companion object {
        const val INDEX_NAME = "tse_transaction_one_live_per_vorgang"
    }
}
