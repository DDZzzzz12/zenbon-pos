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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Verifies case-insensitive global search across entityName, entityId, message. */
class EntryLogSearchFilterTest {
    private val repository = EntryLogRepositoryImpl()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    private suspend fun insert(
        entityId: String,
        entityName: String,
        message: String?,
    ) = dbQuery {
        EntryLogTable.insert {
            it[type] = ChangeEntityType.PRODUCT
            it[operation] = LogOperation.CREATE
            it[EntryLogTable.entityId] = entityId
            it[EntryLogTable.entityName] = entityName
            it[EntryLogTable.message] = message
            it[createdAt] = LocalDateTime(2026, 6, 1, 10, 0, 0)
        }
    }

    private suspend fun searchIds(term: String?): List<String> =
        dbQuery {
            repository
                .findPaginated(EntryLogDto.EntryLogQueryDto(search = term))
                .second
                .map { it.entityId }
        }

    @Test
    fun `search matches entityName case-insensitively`() =
        runBlocking {
            insert("p-1", "Cappuccino", "created")
            insert("p-2", "Espresso", "created")
            assertEquals(listOf("p-1"), searchIds("cappu"))
        }

    @Test
    fun `search matches entityId`() =
        runBlocking {
            insert("ORDER-999", "Table 5", null)
            insert("ORDER-111", "Table 6", null)
            assertEquals(listOf("ORDER-999"), searchIds("999"))
        }

    @Test
    fun `search matches message`() =
        runBlocking {
            insert("p-1", "Item", "price changed to 9.99")
            insert("p-2", "Item2", "name changed")
            assertEquals(listOf("p-1"), searchIds("price"))
        }

    @Test
    fun `blank search returns all rows`() =
        runBlocking {
            insert("p-1", "A", null)
            insert("p-2", "B", null)
            assertEquals(2, searchIds("  ").size)
        }
}
