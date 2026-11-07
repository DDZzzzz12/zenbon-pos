package org.example.project.database

import org.example.project.testutils.SchemaDump
import org.example.project.testutils.Snapshots
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Pins the schema a FRESH install builds on H2.
 *
 * A fresh install never runs Flyway: `initDB` builds the schema from the Exposed objects through
 * `SchemaManager.createTables()` and baselines Flyway at the latest version. So the Exposed objects
 * ARE the production DDL of a new shop, and a refactoring that changes how a foreign key is declared
 * (for example, moving it out of a table object into a host-declared list) changes that DDL unless it
 * is done exactly. The snapshots are the authority: every foreign key with its name, delete and update
 * rule, and every column and index entry.
 *
 * The PostgreSQL twin is [FreshInstallForeignKeySnapshotPostgresTest].
 */
@DisplayName("Fresh install schema — foreign keys, columns and indexes pinned (H2)")
class FreshInstallForeignKeySnapshotTest {
    @BeforeEach
    fun setUp() = TestDatabase.setup()

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    @Test
    fun `fresh install foreign keys are unchanged`() {
        Snapshots.assertMatches("fresh-install-foreign-keys.snapshot.txt", foreignKeys())
    }

    @Test
    fun `fresh install columns and indexes are unchanged`() {
        Snapshots.assertMatches("fresh-install-schema.snapshot.txt", columnsAndIndexes())
    }

    @Test
    fun `the two cross-area foreign keys are present on a fresh install`() {
        assertCrossAreaKeysPresent(foreignKeys())
    }

    /**
     * `resetTables` is broken on H2: its drop list holds `bill_promotion` but not
     * `sales_invoice_allowance_charge`, which references it, and H2 refuses. It has no caller. What is pinned here is
     * that it fails the same way on every call and leaves the same schema behind — H2 commits each
     * DDL statement, so the tables dropped before the failure stay dropped — and which ones those are.
     */
    @Test
    fun `reset tables fails the same way twice`() {
        val first = assertFails { SchemaManager.resetTables(withProduct = true) }
        val keysAfterFirst = foreignKeys()
        val schemaAfterFirst = columnsAndIndexes()

        val second = assertFails { SchemaManager.resetTables(withProduct = true) }

        assertEquals(failureLine(first), failureLine(second), "the second reset must fail exactly like the first")
        assertEquals(keysAfterFirst, foreignKeys(), "a second reset must leave the same foreign keys as the first")
        assertEquals(schemaAfterFirst, columnsAndIndexes(), "a second reset must leave the same columns and indexes")
        Snapshots.assertMatches("reset-tables.h2.snapshot.txt", listOf("failure ${failureLine(first)}") + tablesIn(schemaAfterFirst) + keysAfterFirst)
    }

    /**
     * `recreateAllTables` is broken on H2 too (no caller either): Exposed cannot order the drop
     * around a foreign-key cycle, and H2 refuses to drop a table another one still references.
     *
     * Before Task A7 the drop stopped at `bill`, which `sales_invoice.bill_id` references, because
     * `bill` and `sales_invoice` referenced each other. Since `bill.clears_sales_invoice_id` became a
     * host-declared key that cycle is gone (the key is dropped first, see `CrossAreaForeignKeys`), and
     * the drop now stops at the next one, `table_session` ↔ `r_table`. Pinned: where it stops and what
     * it leaves behind.
     */
    @Test
    fun `recreating all tables fails on H2 where pinned and leaves the pinned schema`() {
        val failure = assertFails { SchemaManager.recreateAllTables(confirmRecreate = true) }

        Snapshots.assertMatches(
            "recreate-all-tables.h2.snapshot.txt",
            listOf("failure ${failureLine(failure)}") + tablesIn(columnsAndIndexes()) + foreignKeys(),
        )
    }

    /** The table names a [SchemaDump.columnsAndIndexes] dump mentions. */
    private fun tablesIn(schema: List<String>): List<String> =
        schema.filter { it.startsWith("column ") }.map { "table " + it.removePrefix("column ").substringBefore('.') }.distinct()

    /** The first line of the root cause's message, cut before H2's echo of the statement. */
    private fun failureLine(failure: Throwable): String {
        var cause = failure
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.message
            .orEmpty()
            .lineSequence()
            .first()
            .substringBefore("; SQL statement")
    }

    private fun assertCrossAreaKeysPresent(keys: List<String>) {
        for (prefix in CROSS_AREA_PREFIXES) {
            assertTrue(keys.any { it.startsWith(prefix) }, "missing foreign key $prefix…; have:\n${keys.joinToString("\n")}")
        }
    }

    private fun foreignKeys(): List<String> = DatabaseFactory.getDataSource().connection.use { SchemaDump.foreignKeys(it) }

    private fun columnsAndIndexes(): List<String> = DatabaseFactory.getDataSource().connection.use { SchemaDump.columnsAndIndexes(it) }

    private companion object {
        val CROSS_AREA_PREFIXES =
            listOf(
                "bill.clears_sales_invoice_id -> sales_invoice.id name=fk_bill_clears_sales_invoice_id__id ",
                "kassenbuch_header.linked_document_id -> documents.id ",
            )
    }
}
