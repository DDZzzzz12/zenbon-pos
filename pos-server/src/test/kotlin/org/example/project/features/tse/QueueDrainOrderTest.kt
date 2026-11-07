package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.features.audit.data.service.TransactionAuditServiceImpl
import org.example.project.features.tse.data.repository.TseSigningQueueRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.domain.models.TseSigningQueue
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals

/** E2E R2-4: a backlog is signed in the order the Vorgänge happened, whatever their type. */
class QueueDrainOrderTest {
    private val device = ScriptedTseSignProvider()
    private lateinit var svc: TseSigningQueueServiceImpl

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        svc =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                device,
                TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json),
            )
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    /** Enqueues a Vorgang and backdates it, the way the E2E backlog built up over the outage. */
    private fun queued(
        ref: String,
        type: TseEntityType,
        createdAt: String,
    ) {
        val id =
            runBlocking {
                svc
                    .enqueue(
                        referenceId = ref,
                        entityType = type,
                        processType = if (type == TseEntityType.BILL) "Kassenbeleg-V1" else "Bestellung-V1",
                        processData = if (type == TseEntityType.BILL) "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar" else "1;\"Pils\";4.50",
                        clientId = "TER-008",
                        reason = TseLateSigningReason.TSE_UNAVAILABLE,
                        reasonText = "test",
                    ).id
            }
        transaction { TseSigningQueue.findById(id)!!.createdAt = LocalDateTime.parse(createdAt) }
    }

    @Test
    fun `the order, its Storno and the bill are signed in the order they happened`() {
        // Enqueued in a DIFFERENT order than they happened, so passing proves the drain follows
        // created_at — not the row id, and not the entity type's priority.
        queued("5179", TseEntityType.BILL, "2026-09-21T11:18:30")
        queued("9317", TseEntityType.ORDER, "2026-09-21T10:51:56")
        // A numeric reference: the queue resolves a non-UUID cancellation reference through its legacy
        // "id;id" parser, and a non-numeric one would fail that parse.
        queued("4711", TseEntityType.CANCELLATION, "2026-09-21T10:53:00")

        runBlocking { svc.processBatch(batchSize = 50) }

        val signedInTanrOrder = transaction { TseTransaction.all().sortedBy { it.transactionNumber }.map { it.referenceId } }
        assertEquals(listOf("9317", "4711", "5179"), signedInTanrOrder)
    }

    @Test
    fun `a device that drops out mid-batch ends the batch, and the rest keep their turn`() {
        queued("1", TseEntityType.ORDER, "2026-09-21T10:00:00")
        queued("2", TseEntityType.ORDER, "2026-09-21T10:01:00")
        device.failNext("start", PosException(PosErrorCode.TSE_CONNECTION_FAILED, HttpStatusCode.ServiceUnavailable, "gone"))

        runBlocking { svc.processBatch(batchSize = 50) }

        val entries = transaction { TseSigningQueue.all().sortedBy { it.referenceId }.map { Triple(it.referenceId, it.signingStatus, it.retryCount) } }
        assertEquals(Triple("1", TseSigningStatus.QUEUED, 1), entries[0])
        assertEquals(Triple("2", TseSigningStatus.PENDING, 0), entries[1], "not attempted, so no retry burned")
        assertEquals(listOf("start"), device.calls)
    }
}
