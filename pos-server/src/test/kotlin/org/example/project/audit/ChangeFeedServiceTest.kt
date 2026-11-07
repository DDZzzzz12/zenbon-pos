package org.example.project.audit

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.audit.data.repository.EntryLogRepositoryImpl
import org.example.project.features.audit.data.service.ChangeFeedServiceImpl
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.example.project.utils.log.EntryLogHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * TDD tests for [ChangeFeedServiceImpl].
 * Write first (red), implement (green).
 */
class ChangeFeedServiceTest {
    private val repository = EntryLogRepositoryImpl()
    private val service = ChangeFeedServiceImpl(repository)

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
    fun `getChanges returns 2 entries and correct highWaterSeq for 2 PRODUCT rows`() =
        runBlocking {
            insertLog(ChangeEntityType.PRODUCT, LogOperation.CREATE, "p1")
            insertLog(ChangeEntityType.PRODUCT, LogOperation.UPDATE, "p1")

            val result = service.getChanges(sinceSeq = 0L, limit = 100)

            assertEquals(2, result.entries.size)
            assertEquals(result.entries.last().seq, result.highWaterSeq)
            assertEquals(result.entries.first().seq, result.minAvailableSeq)
            assertTrue(result.entries[0].seq < result.entries[1].seq, "entries must be ascending by seq")
        }

    @Test
    fun `getChanges returns empty feed with zeroed water marks when table is empty`() =
        runBlocking {
            val result = service.getChanges(sinceSeq = 0L, limit = 100)

            assertEquals(0, result.entries.size)
            assertEquals(0L, result.highWaterSeq)
            assertEquals(0L, result.minAvailableSeq)
        }

    @Test
    fun `highWaterSeq reflects newest client-visible row, not a later audit-only row`() =
        runBlocking {
            insertLog(ChangeEntityType.PRODUCT, LogOperation.CREATE, "p1")
            insertLog(ChangeEntityType.PRODUCT, LogOperation.UPDATE, "p1")
            // Audit-only row inserted AFTER the PRODUCT rows (clientSynced=false).
            // Its id is the table max, but it must NOT leak into highWaterSeq.
            insertLog(ChangeEntityType.EMPLOYEE, LogOperation.CREATE, "e1")

            val result = service.getChanges(sinceSeq = 0L, limit = 100)

            assertEquals(2, result.entries.size, "only the 2 PRODUCT rows are client-visible")
            assertTrue(result.entries.all { it.entityType == ChangeEntityType.PRODUCT })
            // highWaterSeq must be the second PRODUCT row's seq, NOT the later EMPLOYEE id.
            assertEquals(result.entries.last().seq, result.highWaterSeq)
        }

    @Test
    fun `getChanges respects sinceSeq and excludes rows up to and including it`() =
        runBlocking {
            insertLog(ChangeEntityType.PRODUCT, LogOperation.CREATE, "p1")
            insertLog(ChangeEntityType.PRODUCT, LogOperation.UPDATE, "p2")

            val all = service.getChanges(sinceSeq = 0L, limit = 100)
            assertEquals(2, all.entries.size)

            val firstSeq = all.entries.first().seq
            val afterFirst = service.getChanges(sinceSeq = firstSeq, limit = 100)
            assertEquals(1, afterFirst.entries.size, "sinceSeq is exclusive — first row must be excluded")
            assertTrue(afterFirst.entries.all { it.seq > firstSeq })
        }
}
