package org.example.project.database

import org.example.project.features.backup.data.service.ForeignKeyIntegrityCheck
import org.example.project.features.backup.data.service.RestoreRepairs
import org.example.project.testutils.PostgresTestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the restore's foreign-key integrity on real PostgreSQL, in CI.
 *
 * Models the shape a pre-V84 archive produces: `cash_book` empty (the table did not exist when the
 * backup was taken, so no CSV restores it) and `kassenbuch_header.cash_book_id` holding
 * `syntheticFill`'s `0`. Synthetic data only — no PII — so unlike `realdataTest` this runs in CI.
 *
 * This is also the first execution of [ForeignKeyIntegrityCheck]'s `pg_constraint` enumeration
 * against a live catalog and JDBC driver, including the `getArray(...).array as Array<String>` cast.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Restore foreign-key integrity — real PostgreSQL (zonky)")
class RestoreForeignKeyIntegrityTest {
    private lateinit var pg: PostgresTestDatabase

    @BeforeAll
    fun start() {
        pg = PostgresTestDatabase.start()
    }

    @AfterAll
    fun stop() {
        if (::pg.isInitialized) pg.close()
    }

    /** The minimal slice the repair touches: a shop, a cash book table, and headers. */
    private fun seedPreV84Shape(conn: Connection) {
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("DROP TABLE IF EXISTS kassenbuch_header, cash_book, shop_info CASCADE")
            st.execute("CREATE TABLE shop_info (shop_id BIGSERIAL PRIMARY KEY)")
            st.execute(
                """
                CREATE TABLE cash_book (
                    id BIGSERIAL PRIMARY KEY,
                    shop_id BIGINT NOT NULL REFERENCES shop_info (shop_id),
                    name VARCHAR(60) NOT NULL,
                    is_fiscal BOOLEAN NOT NULL,
                    datev_account VARCHAR(5) NOT NULL,
                    close_interval VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
                    created_at TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE kassenbuch_header (
                    id BIGSERIAL PRIMARY KEY,
                    shop_id BIGINT NOT NULL,
                    beleg_nr VARCHAR(20) NOT NULL,
                    cash_book_id BIGINT NOT NULL REFERENCES cash_book (id)
                )
                """.trimIndent(),
            )
            st.execute("INSERT INTO shop_info (shop_id) VALUES (1)")
            // Triggers off is exactly what the restore does, and is the only way a dangling
            // reference can be inserted at all.
            st.execute("ALTER TABLE kassenbuch_header DISABLE TRIGGER ALL")
            st.execute(
                "INSERT INTO kassenbuch_header (shop_id, beleg_nr, cash_book_id) VALUES " +
                    "(1, '2026-07-0001', 0), (1, '2026-07-0002', 0), (1, '2026-07-0003', 0)",
            )
            st.execute("ALTER TABLE kassenbuch_header ENABLE TRIGGER ALL")
        }
    }

    /**
     * The shape a **post**-V84 archive produces: `cash_book` carries rows, including a non-fiscal
     * petty-cash book that owns headers of its own. A dangling header here is genuine corruption,
     * not a missing column.
     *
     * Shop 2 deliberately has NO cash book at all, so the repair's "create a missing Ladenkasse"
     * arm is eligible — proving the guard makes the repair a *strict* no-op, not merely a partial
     * one.
     */
    private fun seedPostV84Shape(conn: Connection) {
        seedPreV84Shape(conn)
        conn.createStatement().use { st ->
            st.execute("INSERT INTO shop_info (shop_id) VALUES (2)")
            st.execute("DELETE FROM kassenbuch_header")
            st.execute(
                "INSERT INTO cash_book (id, shop_id, name, is_fiscal, datev_account) VALUES " +
                    "(1, 1, 'Ladenkasse', TRUE, '1000'), (2, 1, 'Portokasse', FALSE, '1360')",
            )
            st.execute("ALTER TABLE kassenbuch_header DISABLE TRIGGER ALL")
            st.execute(
                "INSERT INTO kassenbuch_header (shop_id, beleg_nr, cash_book_id) VALUES " +
                    "(1, '2026-07-0001', 2), (1, '2026-07-0002', 2), (1, '2026-07-0003', 999)",
            )
            st.execute("ALTER TABLE kassenbuch_header ENABLE TRIGGER ALL")
        }
    }

    private fun countHeadersPointingAtARealBook(conn: Connection): Int = scalar(conn, HEADERS_RESOLVED_SQL)

    private fun scalar(
        conn: Connection,
        sql: String,
    ): Int =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    @Test
    @DisplayName("a dangling reference is detected — this is what shipped")
    fun danglingReferenceIsDetected() {
        pg.dataSource.connection.use { conn ->
            seedPreV84Shape(conn)

            val violations = ForeignKeyIntegrityCheck.findViolations(conn)

            assertTrue(
                violations.any { it.childTable == "kassenbuch_header" && it.orphanCount == 3L },
                "the check must see 3 orphaned headers; got $violations",
            )
            // Proves the ARRAY -> Array<String> cast really produced the column names, rather than
            // an empty/garbled list that would make countOrphans' SQL meaningless.
            assertEquals(
                listOf("cash_book_id"),
                violations.first { it.childTable == "kassenbuch_header" }.childColumns,
                "child columns must come back from pg_constraint as real names",
            )
        }
    }

    @Test
    @DisplayName("the repair rehomes every header into the shop's fiscal book")
    fun repairRehomesEveryHeader() {
        pg.dataSource.connection.use { conn ->
            seedPreV84Shape(conn)

            RestoreRepairs.apply(conn)

            assertEquals(3, countHeadersPointingAtARealBook(conn), "all headers must resolve")
            assertEquals(
                emptyList(),
                ForeignKeyIntegrityCheck.findViolations(conn),
                "no violation may survive the repair",
            )
        }
    }

    @Test
    @DisplayName("the repair is idempotent — a second run creates no second Ladenkasse")
    fun repairIsIdempotent() {
        pg.dataSource.connection.use { conn ->
            seedPreV84Shape(conn)

            RestoreRepairs.apply(conn)
            RestoreRepairs.apply(conn)
            RestoreRepairs.apply(conn)

            // A post-V84 backup DOES carry cash_book.csv. If the repair were not idempotent it
            // would add a duplicate Ladenkasse on every restore, and the fiscal-book lookup takes
            // .firstOrNull() — so which one won would be arbitrary.
            assertEquals(
                1,
                scalar(conn, "SELECT count(*) FROM cash_book WHERE is_fiscal"),
                "exactly one fiscal cash book may exist",
            )
            assertEquals(3, countHeadersPointingAtARealBook(conn))
        }
    }

    @Test
    @DisplayName("a populated cash_book is left strictly alone — no fiscal reclassification")
    fun repairSkipsPopulatedCashBook() {
        pg.dataSource.connection.use { conn ->
            seedPostV84Shape(conn)

            RestoreRepairs.apply(conn)

            // The defect: the old predicate was only "this id resolves to nothing", so a post-V84
            // archive with a missing/empty cash_book.csv moved every non-fiscal header into the
            // fiscal Ladenkasse — a fiscal reclassification (DSFinV-K selects by is_fiscal, the TSE
            // signing queue requires it). Assert WHERE the headers sit, not just how many resolve.
            assertEquals(
                2,
                scalar(conn, "SELECT count(*) FROM kassenbuch_header WHERE cash_book_id = 2"),
                "headers belonging to the non-fiscal Portokasse must stay there",
            )
            assertEquals(
                0,
                scalar(conn, "SELECT count(*) FROM kassenbuch_header WHERE cash_book_id = 1"),
                "no header may be moved into the fiscal Ladenkasse",
            )
            // Strict no-op: shop 2 has no cash book at all, yet the repair must not invent one.
            assertEquals(
                2,
                scalar(conn, "SELECT count(*) FROM cash_book"),
                "the repair must create no cash book once the archive carries the dimension",
            )
            // The genuinely-corrupt header survives for the gate to abort the restore on.
            assertTrue(
                ForeignKeyIntegrityCheck.findViolations(conn).any {
                    it.childTable == "kassenbuch_header" && it.orphanCount == 1L
                },
                "the dangling header must remain visible to the integrity gate",
            )
        }
    }

    @Test
    @DisplayName("an empty cash_book still triggers the repair — the guard did not over-tighten it")
    fun repairStillFiresOnEmptyCashBook() {
        pg.dataSource.connection.use { conn ->
            seedPreV84Shape(conn)
            assertEquals(0, scalar(conn, "SELECT count(*) FROM cash_book"), "precondition: empty")

            RestoreRepairs.apply(conn)

            assertEquals(
                1,
                scalar(conn, "SELECT count(*) FROM cash_book WHERE is_fiscal"),
                "the pre-V84 archive must still get its Ladenkasse",
            )
            assertEquals(3, countHeadersPointingAtARealBook(conn), "all headers must resolve")
        }
    }

    @Test
    @DisplayName("a consistent database yields no violations")
    fun consistentDatabaseIsClean() {
        pg.dataSource.connection.use { conn ->
            seedPreV84Shape(conn)
            RestoreRepairs.apply(conn)

            assertEquals(emptyList(), ForeignKeyIntegrityCheck.findViolations(conn))
        }
    }

    private companion object {
        const val HEADERS_RESOLVED_SQL =
            "SELECT count(*) FROM kassenbuch_header h JOIN cash_book cb ON cb.id = h.cash_book_id"
    }
}
