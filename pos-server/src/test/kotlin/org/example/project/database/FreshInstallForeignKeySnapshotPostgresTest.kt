package org.example.project.database

import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.SchemaDump
import org.example.project.testutils.Snapshots
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.context.stopKoin
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The PostgreSQL twin of [FreshInstallForeignKeySnapshotTest]: a fresh install through the
 * production `initDB` on an empty embedded PostgreSQL, exactly as a new shop, and its schema pinned
 * line by line — every foreign key (JDBC metadata), every constraint as `pg_get_constraintdef`
 * prints it, and every column and index entry.
 *
 * PostgreSQL matters separately because Exposed's DDL differs by dialect: a table in a foreign-key
 * cycle gets its foreign keys as trailing `ALTER TABLE` statements and is dropped with `CASCADE`.
 * Each test gets its own embedded server so the reset and recreate tests start from a fresh install.
 */
@Tag("postgres")
@DisplayName("Fresh install schema — foreign keys, constraints, columns and indexes pinned (real PostgreSQL)")
class FreshInstallForeignKeySnapshotPostgresTest {
    private lateinit var pg: PostgresTestDatabase

    @BeforeEach
    fun freshInstall() {
        pg = PostgresTestDatabase.start()
        stopKoin()
        DatabaseFactory.close()
        initDB(pg.hikariConfig(), runMigrations = true)
    }

    @AfterEach
    fun stop() {
        if (::pg.isInitialized) pg.close()
    }

    @Test
    fun `fresh install foreign keys are unchanged`() {
        Snapshots.assertMatches("fresh-install-foreign-keys.pg.snapshot.txt", foreignKeys())
    }

    @Test
    fun `fresh install constraints columns and indexes are unchanged`() {
        Snapshots.assertMatches("fresh-install-schema.pg.snapshot.txt", schema())
    }

    /**
     * `resetTables` is broken on PostgreSQL as well (it has no caller): its drop list holds
     * `customers` but not `debtor`/`reservations`, which reference it. PostgreSQL DDL is
     * transactional, so the failed reset rolls back completely. Pinned: it fails the same way on every
     * call and changes nothing — in particular it does not lose the two cross-area foreign keys.
     */
    @Test
    fun `reset tables fails the same way twice`() {
        val freshKeys = foreignKeys()
        val freshSchema = schema()

        val first = assertFails { SchemaManager.resetTables(withProduct = true) }
        assertEquals(freshKeys, foreignKeys(), "a failed reset must roll back completely")
        val second = assertFails { SchemaManager.resetTables(withProduct = true) }

        assertEquals(failureLine(first), failureLine(second), "the second reset must fail exactly like the first")
        assertEquals(EXPECTED_RESET_FAILURE, failureLine(first))
        assertEquals(freshKeys, foreignKeys(), "a failed reset must roll back completely")
        assertEquals(freshSchema, schema(), "a failed reset must roll back completely")
        for (prefix in CROSS_AREA_PREFIXES) {
            assertTrue(freshKeys.any { it.startsWith(prefix) }, "missing foreign key $prefix…")
        }
    }

    @Test
    fun `recreating all tables restores the fresh install foreign keys`() {
        val fresh = foreignKeys()
        val freshSchema = schema()

        SchemaManager.recreateAllTables(confirmRecreate = true)

        assertEquals(fresh, foreignKeys())
        assertEquals(freshSchema, schema())
    }

    /** The first line of the root cause's message. */
    private fun failureLine(failure: Throwable): String {
        var cause = failure
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.message
            .orEmpty()
            .lineSequence()
            .first()
    }

    private fun foreignKeys(): List<String> = DatabaseFactory.getDataSource().connection.use { SchemaDump.foreignKeys(it) }

    private fun schema(): List<String> = DatabaseFactory.getDataSource().connection.use { constraintsOf(it) + SchemaDump.columnsAndIndexes(it) }

    /** Every constraint of the public schema: table, name, type and its definition as PostgreSQL prints it. */
    private fun constraintsOf(conn: Connection): List<String> =
        conn
            .createStatement()
            .use { st ->
                st
                    .executeQuery(
                        """
                        SELECT t.relname, c.conname, c.contype, pg_get_constraintdef(c.oid)
                        FROM pg_constraint c
                        JOIN pg_class t ON t.oid = c.conrelid
                        JOIN pg_namespace n ON n.oid = t.relnamespace
                        WHERE n.nspname = 'public'
                        """.trimIndent(),
                    ).use { rs ->
                        buildList {
                            while (rs.next()) {
                                add("constraint ${rs.getString(1)}.${rs.getString(2)} type=${rs.getString(3)} def=${rs.getString(4)}")
                            }
                        }
                    }
            }.sorted()

    private companion object {
        const val EXPECTED_RESET_FAILURE = "ERROR: cannot drop table customers because other objects depend on it"

        val CROSS_AREA_PREFIXES =
            listOf(
                "bill.clears_sales_invoice_id -> sales_invoice.id name=fk_bill_clears_sales_invoice_id__id ",
                "kassenbuch_header.linked_document_id -> documents.id ",
            )
    }
}
