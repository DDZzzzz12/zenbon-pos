package org.example.project.audit

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.audit.data.repository.EntryLogRepositoryImpl
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.example.project.utils.log.EntryLogHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies the change-feed sequence/retention queries on [EntryLogRepositoryImpl]:
 * getChangesSince (client-synced rows only, ascending by id), minSeq/maxSeq.
 */
class ChangeFeedRepositoryTest {
    private val repository = EntryLogRepositoryImpl()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    private suspend fun insertLog(
        type: ChangeEntityType,
        operation: LogOperation,
        entityId: String,
    ) {
        dbQuery {
            EntryLogHelper.logOperation(
                type = type,
                operation = operation,
                entityId = entityId,
                entityName = "$type-$entityId",
            )
        }
    }

    @Test
    fun `getChangesSince returns only client-synced rows in ascending id order`() =
        runBlocking {
            insertLog(ChangeEntityType.PRODUCT, LogOperation.CREATE, "p1")
            insertLog(ChangeEntityType.EMPLOYEE, LogOperation.CREATE, "e1") // audit-only, clientSynced=false
            insertLog(ChangeEntityType.PRODUCT, LogOperation.UPDATE, "p1")

            val changes = dbQuery { repository.getChangesSince(0, 100) }

            assertEquals(2, changes.size, "EMPLOYEE row must be excluded (clientSynced=false)")
            assertTrue(changes.all { it.type == ChangeEntityType.PRODUCT }, "only PRODUCT rows expected")
            assertEquals(
                listOf(LogOperation.CREATE, LogOperation.UPDATE),
                changes.map { it.operation },
                "rows must be ascending by id (insertion order)",
            )
            val ids = changes.map { it.id.value }
            assertEquals(ids.sorted(), ids, "ids must be ascending")
        }

    @Test
    fun `getChangesSince honours sinceSeq and excludes ERROR rows`() =
        runBlocking {
            insertLog(ChangeEntityType.PRODUCT, LogOperation.CREATE, "p1")
            insertLog(ChangeEntityType.PRODUCT, LogOperation.ERROR, "p2") // error rows excluded
            insertLog(ChangeEntityType.PRODUCT, LogOperation.UPDATE, "p3")

            val all = dbQuery { repository.getChangesSince(0, 100) }
            assertEquals(2, all.size, "ERROR rows must be excluded")

            val firstId = all.first().id.value
            val afterFirst = dbQuery { repository.getChangesSince(firstId, 100) }
            assertEquals(1, afterFirst.size, "sinceSeq is exclusive")
            assertTrue(afterFirst.all { it.id.value > firstId })
        }

    @Test
    fun `minSeq and maxSeq are non-null and ordered after inserts`() =
        runBlocking {
            assertEquals(null, dbQuery { repository.minSeq() }, "minSeq null when empty")
            assertEquals(null, dbQuery { repository.maxSeq() }, "maxSeq null when empty")

            insertLog(ChangeEntityType.PRODUCT, LogOperation.CREATE, "p1")
            insertLog(ChangeEntityType.PRODUCT, LogOperation.UPDATE, "p2")

            val min = dbQuery { repository.minSeq() }
            val max = dbQuery { repository.maxSeq() }
            assertNotNull(min)
            assertNotNull(max)
            assertTrue(min!! <= max!!, "min must be <= max")
        }
}
