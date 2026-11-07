package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.features.audit.data.service.TransactionAuditServiceImpl
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.bill.data.repository.BillRepositoryImpl
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.monthlyclose.data.service.checks.UnsignedTseCheck
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.tse.data.repository.TseSigningQueueRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.infrastructure.plugins.TRACE_ID_MDC_KEY
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8 (isolated audit 2026-07-22): an admin could abandon a Vorgang's Nachsignierung invisibly.
 *
 * `cancelEntry` set the queue entry AND the entity to `CANCELLED` with no guard, no reason and —
 * unlike the success path right above it, which logs `TSE_QUEUE_SIGNED` — no
 * `TransactionAuditLog` entry at all. So a Beleg could be left permanently unsigned by deliberate
 * operator action that left no trace of who did it or why.
 *
 * Worse, it then became invisible: `countUnsignedTseInWindow` lists `CANCELLED` among its
 * `signedStatuses`, so `UnsignedTseCheck` skipped it and the monthly close reported **PASSED** over
 * a Beleg carrying no TSE signature — exactly the state §146a AO / KassenSichV exist to prevent.
 *
 * Both halves matter and neither is sufficient alone: an audit entry nobody reads does not stop a
 * close from passing, and a failing close without an audit entry cannot say who abandoned what.
 */
@DisplayName("Abandoned Nachsignierung is recorded and surfaces at the close (M8)")
class CancelEntryAuditTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    /**
     * `MDC.remove` is load-bearing. MDC is thread-local and JUnit reuses the thread, so without it
     * the trace-id test below leaks its id into [`cancelling without a request behind it records no
     * trace id`], which would then pass or fail depending on execution order.
     */
    @AfterEach
    fun teardown() {
        MDC.remove(TRACE_ID_MDC_KEY)
        TestDatabase.teardown()
    }

    /**
     * The real audit service and repository, not the recording fake — this pair is what proves the
     * trace id survives all the way to the `trace_id` COLUMN. A fake would only prove `cancelEntry`
     * passed an argument.
     *
     * It is also the only thing that sees the serialization defect these two tests uncovered:
     * `logTransaction` encoded its `Map<String, Any>` metadata with a serializer for `Any`, threw,
     * and `cancelEntry`'s catch-and-log swallowed it — so NO `TSE_QUEUE_CANCELLED` row was ever
     * written. The pre-existing tests in this class pass a [RecordingAuditService], which never
     * serializes, so the M8 guarantee they assert had not held in production since it was written.
     * Reaching the real row is therefore the regression test for that too: both tests below fail
     * with `Collection is empty` if the encoding regresses.
     */
    private fun realAuditService() = TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json)

    private fun lastAuditRow() =
        transaction {
            TransactionAuditLogTable
                .selectAll()
                .orderBy(TransactionAuditLogTable.id to SortOrder.DESC)
                .limit(1)
                .single()
        }

    private fun enqueueAndCancel(
        svc: TseSigningQueueServiceImpl,
        referenceId: String,
    ) {
        val entryId =
            runBlocking {
                svc
                    .enqueue(
                        referenceId = referenceId,
                        entityType = TseEntityType.BILL,
                        processType = "Kassenbeleg-V1",
                        processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                        clientId = "TERM-CANCEL",
                        reason = TseLateSigningReason.TSE_UNAVAILABLE,
                        reasonText = "test",
                    ).id
            }
        runBlocking { svc.cancelEntry(entryId, employeeId = "emp-7", reason = "device replaced") }
    }

    /**
     * `cancelEntry` is request-backed — its ONLY caller is `TseSigningQueueRoutes`, taking the
     * employee and the mandatory reason from an operator's request via `CancelQueueEntryDialog`. It
     * is not background work, and the design note that once called it that was simply wrong.
     *
     * The row it writes records what the service's own comment calls a permanently unsigned Beleg
     * under §146a AO. That is precisely the row an auditor needs to tie back to the request in which
     * the signature was abandoned, so it must carry the trace id like any other.
     *
     * Asserted over the COLUMN, not over a fake's captured argument: the id crosses `cancelEntry` →
     * `logTransaction` → `repository.create` → `trace_id`, and a break anywhere in that chain leaves
     * a null column while every intermediate signature still compiles.
     */
    @Test
    @DisplayName("abandoning a signature from a request records that request's trace id")
    fun `cancelling under a request records its trace id`() {
        val svc =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                DeadProvider(),
                realAuditService(),
            )

        MDC.put(TRACE_ID_MDC_KEY, CANCEL_TRACE)
        enqueueAndCancel(svc, referenceId = "93")

        val row = lastAuditRow()
        assertEquals(
            "TSE_QUEUE_CANCELLED",
            row[TransactionAuditLogTable.operation],
            "the assertion below means nothing unless this is the abandonment row",
        )
        assertEquals(CANCEL_TRACE, row[TransactionAuditLogTable.traceId])
    }

    /** The complement: nothing is fabricated when there is no request behind the call. */
    @Test
    @DisplayName("abandoning a signature with no request behind it records no trace id")
    fun `cancelling without a request behind it records no trace id`() {
        val svc =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                DeadProvider(),
                realAuditService(),
            )

        enqueueAndCancel(svc, referenceId = "94")

        val row = lastAuditRow()
        assertEquals("TSE_QUEUE_CANCELLED", row[TransactionAuditLogTable.operation])
        assertNull(row[TransactionAuditLogTable.traceId])
    }

    @Test
    fun `cancelling a queue entry writes an audit record naming who and why`() {
        val audit = RecordingAuditService()
        val svc =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                DeadProvider(),
                audit,
            )

        val entryId =
            runBlocking {
                svc
                    .enqueue(
                        referenceId = "91",
                        entityType = TseEntityType.BILL,
                        processType = "Kassenbeleg-V1",
                        processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                        clientId = "TERM-CANCEL",
                        reason = TseLateSigningReason.TSE_UNAVAILABLE,
                        reasonText = "test",
                    ).id
            }

        runBlocking { svc.cancelEntry(entryId, employeeId = "emp-7", reason = "device replaced") }

        assertEquals(
            1,
            audit.entries.size,
            "abandoning a Vorgang's signature is a deliberate fiscal act and must leave a trace — " +
                "the SUCCESS path right above it already logs TSE_QUEUE_SIGNED",
        )
        val (operation, entityId, reason) = audit.entries.single()
        assertEquals("TSE_QUEUE_CANCELLED", operation)
        assertEquals("91", entityId, "the record must name the Vorgang that stays unsigned")
        assertTrue(
            reason?.contains("device replaced") == true,
            "the operator's reason must be preserved verbatim; got $reason",
        )
    }

    @Test
    fun `a blank reason is rejected`() {
        val svc =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                DeadProvider(),
                RecordingAuditService(),
            )
        val entryId =
            runBlocking {
                svc
                    .enqueue(
                        referenceId = "92",
                        entityType = TseEntityType.BILL,
                        processType = "Kassenbeleg-V1",
                        processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                        clientId = "TERM-CANCEL",
                        reason = TseLateSigningReason.TSE_UNAVAILABLE,
                        reasonText = "test",
                    ).id
            }

        val failed =
            runCatching {
                runBlocking { svc.cancelEntry(entryId, employeeId = "emp-7", reason = "  ") }
            }.isFailure

        assertTrue(
            failed,
            "an unexplained abandonment is exactly what the audit trail exists to prevent, so the " +
                "reason cannot be optional",
        )
    }

    /**
     * The other half. An audit entry nobody reads does not stop the period being closed over a
     * permanently unsigned Beleg.
     */
    @Test
    fun `a cancelled signing makes the monthly close report a finding`() {
        val start = LocalDateTime(2026, 7, 1, 0, 0, 0)
        val end = LocalDateTime(2026, 8, 1, 0, 0, 0)

        transaction {
            val admin = TestData.createAdmin()
            TestData.createShopInfo()
            val tws = TestData.createTableWithSession("CANC")
            val session = TableSession.findById(tws.session.id)!!
            val mid = LocalDateTime(2026, 7, 15, 12, 0, 0)
            Bill.new {
                this.employeeId = admin
                this.tableSession = session
                this.billNumber = "CANC-1"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal.ZERO
                this.startTime = mid
                this.endTime = mid
                // The operator abandoned Nachsignierung: this Beleg carries NO TSE signature.
                this.tseSigningStatus = TseSigningStatus.CANCELLED
            }
        }

        val item = transaction { runBlocking { UnsignedTseCheck(BillRepositoryImpl()).run(start, end) } }

        assertEquals(
            MonthlyCloseDto.ChecklistStatus.FAILED,
            item.status,
            "CANCELLED was listed among the check's `signedStatuses`, so the close reported PASSED " +
                "over a Beleg with no TSE signature. A deliberately abandoned signature is still an " +
                "unsigned Beleg (§146a AO).",
        )
        assertEquals(1, item.affectedCount)
    }

    private companion object {
        const val CANCEL_TRACE = "a1d0c6e8-3f21-4b7e-9c55-2f9a7b1e4d30"
    }

    private class DeadProvider : TseSignProvider {
        override suspend fun quickSign(
            processType: String,
            processData: String,
            clientId: String,
        ) = throw UnsupportedOperationException()

        override suspend fun startTransaction(
            processType: String,
            clientId: String,
        ) = throw UnsupportedOperationException()

        override suspend fun updateTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
        ) = throw UnsupportedOperationException()

        override suspend fun finishTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
            processType: String,
        ) = throw UnsupportedOperationException()

        override suspend fun checkHealth(): TseDto.HealthCheckResultDto? = null

        override fun isConnected(): Boolean = false

        override fun getCachedMode(): String = "SIMULATION"

        override fun registerConnectionListener() = Unit

        override suspend fun restartConnection(): TseDto.HealthCheckResultDto? = null

        override fun close() = Unit
    }

    /** Captures (operation, entityId, reason). */
    private class RecordingAuditService : TransactionAuditService {
        val entries = mutableListOf<Triple<String, String, String?>>()

        override suspend fun logTransaction(
            employeeId: String,
            employeeName: String?,
            employeeNumber: String,
            authorizationType: String,
            authorizingManagerId: String,
            operation: String,
            entityType: String,
            entityId: String,
            entityName: String?,
            beforeState: Any?,
            afterState: Any?,
            deviceId: String?,
            ipAddress: String?,
            reason: String?,
            amountImpact: Double?,
            fiscalRecordId: String?,
            metadata: Map<String, Any>?,
            traceId: String?,
        ): String {
            entries.add(Triple(operation, entityId, reason))
            return "AUDIT-REC"
        }

        override suspend fun getTransactionLogs(query: TransactionAuditDto.TransactionAuditQueryDto): List<TransactionAuditDto.TransactionAuditLogDto> =
            emptyList()

        override suspend fun getTransactionById(transactionId: String): TransactionAuditDto.TransactionAuditLogDto = throw NotImplementedError()

        override suspend fun getEmployeeTransactions(
            employeeId: String,
            limit: Int,
        ): List<TransactionAuditDto.TransactionAuditLogDto> = emptyList()

        override suspend fun getEntityTransactions(
            entityType: String,
            entityId: String,
        ): List<TransactionAuditDto.TransactionAuditLogDto> = emptyList()

        override suspend fun getPaginatedTransactionLogs(
            query: TransactionAuditDto.PaginatedTransactionAuditQueryDto,
        ): TransactionAuditDto.PaginatedTransactionAuditResponseDto = throw NotImplementedError()
    }
}
