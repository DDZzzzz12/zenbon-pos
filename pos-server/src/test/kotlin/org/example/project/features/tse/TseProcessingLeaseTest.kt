package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
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
import org.example.project.features.tse.domain.repository.TseSigningQueueRepository
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
 * PROCESSING is a LEASE, not a grave.
 *
 * The signer commits PROCESSING *before* the device call, so a kill during signing used to strand
 * the entry forever: `TseSigningStatus.PROCESSING.canRetry()` is false and `findReadyForProcessing`
 * selected only PENDING/QUEUED, so a fiscal Vorgang stayed unsigned, was never retried, never
 * reached FAILED, and raised no alarm. Before the restructure the one big transaction rolled the
 * status back; now the lease does that job, bounded by
 * [TseSigningQueueRepository.PROCESSING_LEASE_MINUTES].
 *
 * Resuming is safe because the signer adopts the STARTED row and finishes the SAME transaction
 * number rather than signing the payload a second time.
 */
@DisplayName("A crashed PROCESSING entry is reclaimed once its lease expires")
class TseProcessingLeaseTest {
    private lateinit var device: ScriptedTseSignProvider

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        device = ScriptedTseSignProvider()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun service() =
        TseSigningQueueServiceImpl(
            TseSigningQueueRepositoryImpl(),
            TseTransactionRepositoryImpl(),
            device,
            TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json),
        )

    private fun enqueued(reference: String): Long =
        runBlocking {
            service()
                .enqueue(
                    referenceId = reference,
                    entityType = TseEntityType.ORDER,
                    processType = "Bestellung-V1",
                    processData = "1;\"Pils\";4.50",
                    clientId = "TERM-LEASE",
                    reason = TseLateSigningReason.TSE_UNAVAILABLE,
                    reasonText = "killed mid-signing",
                ).id
        }

    /** Puts the entry in the state a kill during the device call leaves behind. */
    private fun markProcessing(
        entryId: Long,
        minutesAgo: Long,
    ) = transaction {
        TseSigningQueue.findById(entryId)!!.apply {
            signingStatus = TseSigningStatus.PROCESSING
            lastAttemptAt =
                currentTimeStamp()
                    .toInstant(TimeZone.currentSystemDefault())
                    .minus(minutesAgo, DateTimeUnit.MINUTE)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
        }
    }

    private fun statusOf(entryId: Long) = transaction { TseSigningQueue.findById(entryId)!!.signingStatus }

    @Test
    fun `an expired lease is picked up again and the Vorgang is signed`() {
        val entryId = enqueued("4201")
        markProcessing(entryId, minutesAgo = TseSigningQueueRepository.PROCESSING_LEASE_MINUTES + 5)

        val ready = transaction { runBlocking { TseSigningQueueRepositoryImpl().findReadyForProcessing(50) } }
        assertTrue(ready.any { it.id.value == entryId }, "a stranded PROCESSING entry must become claimable again")

        runBlocking { service().processEntry(entryId) }

        assertEquals(TseSigningStatus.SIGNED, statusOf(entryId))
        assertEquals(listOf("start", "finish:100:Bestellung-V1"), device.calls)
    }

    @Test
    fun `a lease still held is left alone`() {
        val entryId = enqueued("4202")
        markProcessing(entryId, minutesAgo = 1)

        val ready = transaction { runBlocking { TseSigningQueueRepositoryImpl().findReadyForProcessing(50) } }
        assertTrue(ready.none { it.id.value == entryId }, "a worker may still be inside the device call")

        runBlocking { service().processEntry(entryId) }

        assertEquals(TseSigningStatus.PROCESSING, statusOf(entryId))
        assertEquals(emptyList(), device.calls, "a second worker must not race the first one's device call")
    }
}
