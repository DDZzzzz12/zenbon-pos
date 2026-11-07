package org.example.project.audit

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.setup.EntryLogDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.data.repository.EntryLogRepositoryImpl
import org.example.project.features.audit.database.tables.EntryLogTable
import org.example.project.features.setup.data.service.EntryLogServiceImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Keyset (cursor) pagination tests for the entry-log read path.
 *
 * Walks multiple pages via the opaque `nextCursor` returned by
 * [EntryLogServiceImpl.getPaginatedLogs] and asserts stable
 * `(created_at DESC, id DESC)` ordering with no duplicates and no gaps —
 * including across rows that share the same created_at second (id tiebreaker).
 */
class EntryLogCursorPaginationTest {
    private val repository = EntryLogRepositoryImpl()
    private val service = EntryLogServiceImpl(repository)

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    private suspend fun insert(
        entityId: String,
        createdAt: LocalDateTime,
    ) = dbQuery {
        EntryLogTable.insert {
            it[type] = ChangeEntityType.PRODUCT
            it[operation] = LogOperation.CREATE
            it[EntryLogTable.entityId] = entityId
            it[entityName] = "name-$entityId"
            it[EntryLogTable.createdAt] = createdAt
        }
    }

    /**
     * Seeds five rows: a/b/c share one timestamp (insertion order a, b, c — so
     * ascending ids), d and e are strictly older. Expected full order under
     * (created_at DESC, id DESC): c, b, a, d, e.
     */
    private suspend fun seedFiveRows() {
        val shared = LocalDateTime(2026, 6, 1, 12, 0, 0)
        insert("a", shared)
        insert("b", shared)
        insert("c", shared)
        insert("d", LocalDateTime(2026, 6, 1, 11, 0, 0))
        insert("e", LocalDateTime(2026, 6, 1, 10, 0, 0))
    }

    @Test
    fun `cursor pages have stable ordering with no duplicates or gaps`() =
        runBlocking {
            seedFiveRows()

            val page1 = service.getPaginatedLogs(EntryLogDto.EntryLogQueryDto(pageSize = 2))
            assertEquals(listOf("c", "b"), page1.logs.map { it.entityId })
            assertNotNull(page1.nextCursor, "full first page must carry a nextCursor")

            val page2 =
                service.getPaginatedLogs(
                    EntryLogDto.EntryLogQueryDto(pageSize = 2, cursor = page1.nextCursor),
                )
            assertEquals(listOf("a", "d"), page2.logs.map { it.entityId })
            assertNotNull(page2.nextCursor, "middle page must carry a nextCursor")

            val page3 =
                service.getPaginatedLogs(
                    EntryLogDto.EntryLogQueryDto(pageSize = 2, cursor = page2.nextCursor),
                )
            assertEquals(listOf("e"), page3.logs.map { it.entityId })
            assertNull(page3.nextCursor, "last page must not carry a nextCursor")

            // No duplicates, no gaps across the concatenation.
            val all = (page1.logs + page2.logs + page3.logs).map { it.entityId }
            assertEquals(listOf("c", "b", "a", "d", "e"), all)
        }

    @Test
    fun `cursor mode ignores page offset`() =
        runBlocking {
            seedFiveRows()

            val page1 = service.getPaginatedLogs(EntryLogDto.EntryLogQueryDto(pageSize = 2))
            // A bogus page number must have no effect once a cursor is supplied.
            val page2 =
                service.getPaginatedLogs(
                    EntryLogDto.EntryLogQueryDto(pageSize = 2, page = 99, cursor = page1.nextCursor),
                )
            assertEquals(listOf("a", "d"), page2.logs.map { it.entityId })
        }

    @Test
    fun `totalItems stays stable across cursor pages and hasNext follows look-ahead`() =
        runBlocking {
            seedFiveRows()

            val page1 = service.getPaginatedLogs(EntryLogDto.EntryLogQueryDto(pageSize = 2))
            assertEquals(5, page1.pagination.totalItems)
            assertTrue(page1.pagination.hasNext)

            val page2 =
                service.getPaginatedLogs(
                    EntryLogDto.EntryLogQueryDto(pageSize = 2, cursor = page1.nextCursor),
                )
            assertEquals(5, page2.pagination.totalItems, "count excludes the cursor predicate")
            assertTrue(page2.pagination.hasNext)
            assertTrue(page2.pagination.hasPrevious, "cursor implies a previous page")

            val page3 =
                service.getPaginatedLogs(
                    EntryLogDto.EntryLogQueryDto(pageSize = 2, cursor = page2.nextCursor),
                )
            assertEquals(false, page3.pagination.hasNext, "look-ahead detects the end")
        }

    @Test
    fun `cursor respects active filters`() =
        runBlocking {
            seedFiveRows()
            // A row of a different type must never surface in the filtered walk.
            dbQuery {
                EntryLogTable.insert {
                    it[type] = ChangeEntityType.EMPLOYEE
                    it[operation] = LogOperation.CREATE
                    it[entityId] = "x"
                    it[entityName] = "name-x"
                    it[createdAt] = LocalDateTime(2026, 6, 1, 12, 0, 0)
                }
            }

            val query = EntryLogDto.EntryLogQueryDto(type = "PRODUCT", pageSize = 3)
            val page1 = service.getPaginatedLogs(query)
            val page2 = service.getPaginatedLogs(query.copy(cursor = page1.nextCursor))
            val all = (page1.logs + page2.logs).map { it.entityId }
            assertEquals(listOf("c", "b", "a", "d", "e"), all)
            assertNull(page2.nextCursor)
        }

    @Test
    fun `offset mode still works and emits a usable nextCursor`() =
        runBlocking {
            seedFiveRows()

            // Plain offset paging is unchanged.
            val offsetPage2 = service.getPaginatedLogs(EntryLogDto.EntryLogQueryDto(page = 1, pageSize = 2))
            assertEquals(listOf("a", "d"), offsetPage2.logs.map { it.entityId })
            assertEquals(5, offsetPage2.pagination.totalItems)

            // Its nextCursor lets a client switch to keyset paging mid-walk.
            val keysetPage3 =
                service.getPaginatedLogs(
                    EntryLogDto.EntryLogQueryDto(pageSize = 2, cursor = offsetPage2.nextCursor),
                )
            assertEquals(listOf("e"), keysetPage3.logs.map { it.entityId })
        }

    @Test
    fun `malformed cursor is rejected with 400 INVALID_REQUEST`() =
        runBlocking {
            seedFiveRows()

            val ex =
                assertFailsWith<PosException> {
                    service.getPaginatedLogs(EntryLogDto.EntryLogQueryDto(pageSize = 2, cursor = "not-a-cursor"))
                }
            assertEquals(PosErrorCode.INVALID_REQUEST, ex.code)
            assertEquals(HttpStatusCode.BadRequest, ex.httpStatus)
        }
}
