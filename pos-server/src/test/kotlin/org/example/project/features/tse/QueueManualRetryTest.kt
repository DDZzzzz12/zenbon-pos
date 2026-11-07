package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.features.audit.data.service.TransactionAuditServiceImpl
import org.example.project.features.tse.data.repository.TseSigningQueueRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.domain.models.TseSigningQueue
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E round 4, R4-8. Straight after the station was registered, "Erneut versuchen" processed 0
 * entries — both were in back-off until the next scheduled cycle — and the queue status named the
 * NEWEST pending entry as the oldest.
 */
@DisplayName("Signing queue — a manual retry does not wait out the back-off; the oldest entry is the oldest")
class QueueManualRetryTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun service() =
        TseSigningQueueServiceImpl(
            TseSigningQueueRepositoryImpl(),
            TseTransactionRepositoryImpl(),
            ScriptedTseSignProvider(),
            TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json),
        )

    private fun enqueued(reference: String): Long =
        runBlocking {
            service()
                .enqueue(
                    referenceId = reference,
                    entityType = TseEntityType.KASSENBUCH,
                    processType = "Kassenbeleg-V1",
                    processData = "Beleg^-5.95_0.00_0.00_0.00_0.00^-5.95:Bar",
                    clientId = "TER-002",
                    reason = TseLateSigningReason.TSE_NOT_OPERATIONAL,
                    reasonText = "client not registered",
                ).id
        }

    /** The state a failed attempt leaves: QUEUED, next retry a few minutes out. */
    private fun inBackoff(entryId: Long) =
        transaction {
            TseSigningQueue.findById(entryId)!!.apply {
                signingStatus = TseSigningStatus.QUEUED
                nextRetryAt =
                    currentTimeStamp()
                        .toInstant(TimeZone.currentSystemDefault())
                        .plus(5, DateTimeUnit.MINUTE)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
            }
        }

    private fun ready(ignoreBackoff: Boolean) =
        transaction { runBlocking { TseSigningQueueRepositoryImpl().findReadyForProcessing(50, ignoreBackoff) } }.map { it.id.value }

    @Test
    fun `an entry in back-off waits for the scheduled cycle but not for a manual retry`() {
        val entryId = enqueued("4")
        inBackoff(entryId)

        assertTrue(ready(ignoreBackoff = false).isEmpty(), "the scheduled cycle keeps the back-off")
        assertEquals(listOf(entryId), ready(ignoreBackoff = true), "the operator's retry does not")
    }

    @Test
    fun `the status names the oldest waiting entry, queued ones included`() {
        val older = enqueued("4")
        enqueued("5")
        inBackoff(older)

        val status = runBlocking { service().getQueueStatus() }

        val olderCreatedAt = transaction { TseSigningQueue.findById(older)!!.createdAt.toString() }
        assertEquals(olderCreatedAt, status.oldestPendingAge)
    }
}
