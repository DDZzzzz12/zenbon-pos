package org.example.project.audit

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.setup.EntryLogDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.audit.data.repository.EntryLogRepositoryImpl
import org.example.project.features.audit.database.tables.EntryLogTable
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stored `entry_log.type`/`operation` this build does not recognise must not fail the change-log
 * query.
 *
 * The defect: `EntryLogTable` mapped both columns with `enumerationByName`, whose `valueFromDB`
 * throws on an unmatched string. `findPaginated` — what the operator's Änderungen screen calls —
 * selects every row with no type filter, so a handful of stale rows returned a 500 for the whole
 * screen. Observed live as `PRINTERLOCATION can't be associated with any from enum
 * ChangeEntityType` on a database restored from a pre-V80 backup; the restore reloads the archive's
 * rows *after* migrations have run, so V80's rename cannot reach them.
 *
 * H2 leg (`:pos-server:test`) on purpose: nothing here is dialect-specific — the values are stored
 * as plain `VARCHAR(50)` on both engines — so there is no reason to hide it behind
 * `@Tag("postgres")`.
 */
class EntryLogUnknownEnumValueTest {
    private val repository = EntryLogRepositoryImpl()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    /** Writes through the column's own `toDb`, i.e. exactly the bytes production stores. */
    private suspend fun insert(
        type: ChangeEntityType,
        id: String,
    ) = dbQuery {
        EntryLogTable.insert {
            it[EntryLogTable.type] = type
            it[EntryLogTable.operation] = LogOperation.CREATE
            it[EntryLogTable.entityId] = id
            it[EntryLogTable.entityName] = "row-$id"
            it[EntryLogTable.message] = null
            it[EntryLogTable.createdAt] = LocalDateTime(2026, 6, 1, 10, 0, 0)
        }
    }

    /**
     * Stores a string no enum constant matches — the only way to reach the fallback, since every
     * value reachable through the typed column is by definition a name this build knows.
     */
    private fun insertRaw(
        type: String,
        operation: String,
        id: String,
    ) = transaction {
        // Identifiers come from Exposed's own quoting, never spelled out: it quotes the columns it
        // treats as keywords (`type`, `operation`) and leaves the rest bare, while H2 folds an
        // unquoted identifier to upper case. A hand-written `type` therefore looks for TYPE and
        // misses the quoted lower-case column Exposed created.
        exec(
            "INSERT INTO ${EntryLogTable.tableName} " +
                "(${identity(EntryLogTable.type)}, ${identity(EntryLogTable.operation)}, " +
                "${identity(EntryLogTable.entityId)}, ${identity(EntryLogTable.entityName)}, " +
                "${identity(EntryLogTable.createdAt)}) " +
                "VALUES ('$type', '$operation', '$id', 'row-$id', TIMESTAMP '2026-06-01 10:00:00')",
        )
    }

    private suspend fun allRows() = dbQuery { repository.findPaginated(EntryLogDto.EntryLogQueryDto()).second }

    @Test
    fun `a legacy PRINTERLOCATION row does not fail the query and keeps its own label`() =
        runBlocking {
            insert(ChangeEntityType.PRODUCT, "p-1")
            insertRaw(type = "PRINTERLOCATION", operation = "CREATE", id = "pl-1")
            insert(ChangeEntityType.EMPLOYEE, "e-1")

            val rows = allRows()

            // The whole point: the neighbours come back too. Before the fix this call threw.
            assertEquals(
                setOf("p-1", "pl-1", "e-1"),
                rows.map { it.entityId }.toSet(),
                "every row must survive a stale neighbour",
            )
            assertEquals(
                ChangeEntityType.PRINTERLOCATION,
                rows.single { it.entityId == "pl-1" }.type,
                "a retired spelling reads back as itself, not as UNKNOWN",
            )
        }

    @Test
    fun `every retired spelling is readable`() =
        runBlocking {
            val retired =
                listOf(
                    "SHOP",
                    "PRODUCTEXTRAS",
                    "PAYMENTTYPE",
                    "ROOMELEMENT",
                    "PRINTERLOCATION",
                    "PRODUCT_CATEGORY",
                )
            retired.forEachIndexed { i, name -> insertRaw(type = name, operation = "UPDATE", id = "r-$i") }

            val byEntity = allRows().associate { it.entityId to it.type.name }

            assertEquals(retired.size, byEntity.size, "no retired spelling may be dropped")
            retired.forEachIndexed { i, name ->
                assertEquals(name, byEntity["r-$i"], "$name must read back as itself")
            }
        }

    @Test
    fun `an unrecognised type degrades to UNKNOWN instead of throwing`() =
        runBlocking {
            insert(ChangeEntityType.PRODUCT, "p-1")
            insertRaw(type = "SOME_FUTURE_RENAME", operation = "CREATE", id = "x-1")

            val rows = allRows()

            assertEquals(2, rows.size, "the unknown row is rendered, never dropped")
            assertEquals(ChangeEntityType.UNKNOWN, rows.single { it.entityId == "x-1" }.type)
            assertEquals(ChangeEntityType.PRODUCT, rows.single { it.entityId == "p-1" }.type)
        }

    @Test
    fun `an unrecognised operation degrades to ERROR instead of throwing`() =
        runBlocking {
            insertRaw(type = "PRODUCT", operation = "RENAMED_LATER", id = "x-1")

            val rows = allRows()

            assertEquals(1, rows.size)
            assertEquals(LogOperation.ERROR, rows.single().operation)
        }

    @Test
    fun `retired rows stay out of the client change feed`() =
        runBlocking {
            insert(ChangeEntityType.PRODUCT, "p-1")
            insertRaw(type = "PRODUCTEXTRAS", operation = "CREATE", id = "pe-1")
            insertRaw(type = "SOME_FUTURE_RENAME", operation = "CREATE", id = "x-1")

            val fed = dbQuery { repository.getChangesSince(sinceSeq = 0L, limit = 100) }.map { it.entityId }

            // clientSynced = false on every retired spelling and on UNKNOWN. Marking them synced
            // would newly replay months-old changes to every connected till; this is a read fix.
            assertEquals(listOf("p-1"), fed, "only current client-synced types reach the feed")
            assertTrue(fed.none { it == "pe-1" || it == "x-1" })
        }
}
