package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.tse.data.repository.TseSigningQueueRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseSignRequest
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.TRACE_ID_MDC_KEY
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M7 (isolated audit 2026-07-22): TSE signing has no idempotency.
 *
 * `quickSign` maps EVERY transport failure to a `PosException`, including a read
 * timeout that occurs *after* the request reached the device — i.e. after the TSE already signed
 * and burned a transaction number and signature counter. The caller classifies that as
 * `TseSignOutcome.Failed`, the resolver enqueues, and `attemptTseSigning` calls `quickSign` **again**
 * with the same payload. (`openTseTransactionNumber` is always null since the quick-sale removal,
 * so the finish-the-open-transaction branch is dead.) The TSE ends up holding TWO signed
 * Kassenbeleg-V1 transactions for ONE Vorgang, and the export references only the second — phantom
 * duplicated revenue in a TAR-vs-DSFinV-K reconciliation, and permanent, because WORM data cannot
 * be corrected.
 *
 * `TseTransaction` records no `referenceId`, so after a lost response there is no local trace at
 * all to detect the prior signature — nothing was persisted. The outcome is genuinely UNKNOWN and
 * cannot be resolved from our side.
 *
 * The distinguishing signal already exists in `mapNetworkFailure`:
 *
 *  - `TSE_COMMUNICATION_TIMEOUT` — the request was sent and no response came back. The device MAY
 *    have signed. Re-signing risks a permanent duplicate.
 *  - `TSE_CONNECTION_FAILED` — connection refused / HTTP error / connect timeout. The device did
 *    not process it. Re-signing is safe and is the normal outage-recovery path.
 *
 * Decision: keep retrying — a Vorgang must not stay unsigned — but RECORD the risk, so a duplicate
 * is explainable to an auditor reconciling the TAR export against the DSFinV-K data instead of
 * sitting unexplained in the WORM. `TSE_CONNECTION_FAILED` carries no such risk and stays silent.
 *
 * Separately, `tse_transaction` now records `referenceId`/`entityType` (V101), so a signature that
 * DID persist while its entity link did not is adopted rather than re-signed. That closes every
 * duplicate path except the genuinely unobservable one.
 */
@DisplayName("TSE signing records the duplicate risk it cannot rule out (M7)")
class SigningIdempotencyTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    /** `MDC.remove` is load-bearing: MDC is thread-local and JUnit reuses the thread. */
    @AfterEach
    fun teardown() {
        MDC.remove(TRACE_ID_MDC_KEY)
        TestDatabase.teardown()
    }

    private fun service(provider: TseSignProvider) =
        TseSigningQueueServiceImpl(
            TseSigningQueueRepositoryImpl(),
            TseTransactionRepositoryImpl(),
            provider,
            NoOpTransactionAuditService(),
        )

    @Test
    fun `a lost response is retried but records the duplicate risk`() {
        val audit = RecordingAuditService()
        val provider = CountingProvider(PosErrorCode.TSE_COMMUNICATION_TIMEOUT)
        val svc =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                provider,
                audit,
            )

        val entryId =
            runBlocking {
                svc
                    .enqueue(
                        referenceId = "77",
                        entityType = TseEntityType.BILL,
                        processType = "Kassenbeleg-V1",
                        processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                        clientId = "TERM-IDEM",
                        reason = TseLateSigningReason.TSE_UNAVAILABLE,
                        reasonText = "test",
                    ).id
            }

        runCatching { runBlocking { svc.processEntry(entryId) } }

        assertEquals(
            1,
            provider.quickSignCalls,
            "retry is deliberately preserved — a Vorgang must not stay unsigned",
        )
        assertEquals(
            listOf(TseSigningQueueServiceImpl.TSE_POSSIBLE_DOUBLE_SIGN to "77"),
            audit.entries,
            "the request reached the device and no response came back, so the TSE may already hold " +
                "a signature for this payload. The retry is allowed, but the possible duplicate MUST " +
                "be recorded — otherwise it sits unexplained in the WORM where an auditor finds it " +
                "and nobody can account for it.",
        )
    }

    /**
     * A signature that reached the device AND persisted, but whose entity link did not, must be
     * ADOPTED. Before `referenceId` existed it was indistinguishable from "never signed".
     */
    @Test
    fun `an already-persisted signature is adopted instead of re-signed`() {
        val audit = RecordingAuditService()
        // The signature reaches the database through the production writer, not a hand-built row.
        // A fixture that set entity_type by hand hid that no writer ever did (E1).
        val signingDevice = ScriptedTseSignProvider()
        runBlocking {
            TseVorgangSigner(signingDevice, TseTransactionRepositoryImpl(), audit).sign(
                TseSignRequest("79", TseEntityType.BILL, "Kassenbeleg-V1", "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar", "TERM-IDEM"),
            )
        }
        val provider = CountingProvider(PosErrorCode.TSE_CONNECTION_FAILED)
        val svc =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                provider,
                audit,
            )

        val entryId =
            runBlocking {
                svc
                    .enqueue(
                        referenceId = "79",
                        entityType = TseEntityType.BILL,
                        processType = "Kassenbeleg-V1",
                        processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                        clientId = "TERM-IDEM",
                        reason = TseLateSigningReason.TSE_UNAVAILABLE,
                        reasonText = "test",
                    ).id
            }

        runCatching { runBlocking { svc.processEntry(entryId) } }

        assertEquals(
            0,
            provider.quickSignCalls,
            "a signature already exists for this Vorgang — asking the device for a second one " +
                "would put a permanent duplicate in the WORM",
        )
    }

    @Test
    fun `a refused connection is still retried — that is the normal outage path`() {
        val provider = CountingProvider(PosErrorCode.TSE_CONNECTION_FAILED)
        val svc = service(provider)

        val entryId =
            runBlocking {
                svc
                    .enqueue(
                        referenceId = "78",
                        entityType = TseEntityType.BILL,
                        processType = "Kassenbeleg-V1",
                        processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                        clientId = "TERM-IDEM",
                        reason = TseLateSigningReason.TSE_UNAVAILABLE,
                        reasonText = "test",
                    ).id
            }

        runCatching { runBlocking { svc.processEntry(entryId) } }

        assertEquals(
            1,
            provider.quickSignCalls,
            "a refused connection means the device never processed the request, so Nachsignieren " +
                "must still work — suppressing it would strand every ordinary TSE outage",
        )
    }

    @Test
    fun `a refused connection records no duplicate risk`() {
        val audit = RecordingAuditService()
        val svc =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                CountingProvider(PosErrorCode.TSE_CONNECTION_FAILED),
                audit,
            )
        val entryId =
            runBlocking {
                svc
                    .enqueue(
                        referenceId = "80",
                        entityType = TseEntityType.BILL,
                        processType = "Kassenbeleg-V1",
                        processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                        clientId = "TERM-IDEM",
                        reason = TseLateSigningReason.TSE_UNAVAILABLE,
                        reasonText = "test",
                    ).id
            }
        runCatching { runBlocking { svc.processEntry(entryId) } }

        assertEquals(
            emptyList(),
            audit.entries,
            "the device never processed the request, so there is no duplicate to explain — " +
                "flagging it would train the operator to ignore the signal",
        )
    }

    /**
     * A MANUAL retry is request-backed: `retryEntry`'s only caller is `TseSigningQueueRoutes`, and it
     * runs `processEntry` synchronously inside that request. The design note that called the whole
     * TSE queue "work with no request behind it" was wrong about this entry point too.
     *
     * The trace id is captured at the TOP of `retryEntry`, before its first `dbQuery`, and passed
     * down — not re-read at the audit site, which sits two suspension points away inside
     * `processEntry`'s own transaction, where MDC may be gone.
     */
    @Test
    @DisplayName("a manual retry carries the operator's trace id onto the duplicate-risk record")
    fun `a manual retry records the request trace id`() {
        val audit = RecordingAuditService()
        val svc =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                CountingProvider(PosErrorCode.TSE_COMMUNICATION_TIMEOUT),
                audit,
            )

        val entryId =
            runBlocking {
                svc
                    .enqueue(
                        referenceId = "78",
                        entityType = TseEntityType.BILL,
                        processType = "Kassenbeleg-V1",
                        processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                        clientId = "TERM-IDEM",
                        reason = TseLateSigningReason.TSE_UNAVAILABLE,
                        reasonText = "test",
                    ).id
            }

        MDC.put(TRACE_ID_MDC_KEY, RETRY_TRACE)
        runCatching { runBlocking { svc.retryEntry(entryId) } }

        assertEquals(
            listOf(TseSigningQueueServiceImpl.TSE_POSSIBLE_DOUBLE_SIGN to "78"),
            audit.entries,
            "the assertion below means nothing unless the duplicate-risk row was the one written",
        )
        assertEquals(RETRY_TRACE, audit.traceIds.single())
    }

    /**
     * The complement, and the reason the sweeper passes an explicit null rather than reading MDC.
     *
     * MDC is deliberately POLLUTED here. `processBatch` runs on a pooled dispatcher, so a thread it
     * lands on may still hold a trace id left by whichever request used it last; reading MDC inside
     * the sweeper would stamp that unrelated id onto a fiscal row, which is worse than no id at all.
     * This test fails if `processBatch` is ever changed to call the MDC-reading overload.
     */
    @Test
    @DisplayName("the sweeper records no trace id, even on a thread that has one")
    fun `the sweeper records no trace id`() {
        val audit = RecordingAuditService()
        val svc =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                CountingProvider(PosErrorCode.TSE_COMMUNICATION_TIMEOUT, healthy = true),
                audit,
            )

        runBlocking {
            svc.enqueue(
                referenceId = "79",
                entityType = TseEntityType.BILL,
                processType = "Kassenbeleg-V1",
                processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                clientId = "TERM-IDEM",
                reason = TseLateSigningReason.TSE_UNAVAILABLE,
                reasonText = "test",
            )
        }

        MDC.put(TRACE_ID_MDC_KEY, "stale-id-from-an-unrelated-request")
        runCatching { runBlocking { svc.processBatch(batchSize = 10) } }

        assertEquals(
            listOf(TseSigningQueueServiceImpl.TSE_POSSIBLE_DOUBLE_SIGN to "79"),
            audit.entries,
            "the sweeper must actually have reached the audit site, or the null below is vacuous",
        )
        assertNull(audit.traceIds.single(), "a stale id on a pooled thread must not reach a fiscal row")
    }

    private companion object {
        const val RETRY_TRACE = "7c2e91b4-0a55-4d38-8f61-3e0b9d47a112"
    }

    /**
     * Counts device calls and fails each one with the given code.
     *
     * [healthy] exists only so [`the sweeper records no trace id, even on a thread that has one`]
     * can drive `processBatch`, which returns early unless `checkHealth()?.healthy == true`.
     * Defaulted false so every pre-existing use is unchanged.
     */
    private class CountingProvider(
        private val code: PosErrorCode,
        private val healthy: Boolean = false,
    ) : TseSignProvider {
        var quickSignCalls = 0
            private set

        override suspend fun quickSign(
            processType: String,
            processData: String,
            clientId: String,
        ): TseDto.SignResultDto {
            quickSignCalls++
            throw PosException(code, HttpStatusCode.ServiceUnavailable, "simulated $code")
        }

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

        override suspend fun checkHealth(): TseDto.HealthCheckResultDto? =
            if (!healthy) {
                null
            } else {
                TseDto.HealthCheckResultDto(
                    healthy = true,
                    initialized = true,
                    validTime = true,
                    passedSelfTest = true,
                    remainingSignatures = 1_000L,
                    availableCapacity = 1_000L,
                )
            }

        override fun isConnected(): Boolean = true

        override fun getCachedMode(): String = "SIMULATION"

        override fun registerConnectionListener() = Unit

        override suspend fun restartConnection(): TseDto.HealthCheckResultDto? = null

        override fun close() = Unit
    }

    /** Captures (operation, entityId) so the test can assert WHAT was recorded, not just that it was. */
    private class RecordingAuditService : TransactionAuditService by NoOpTransactionAuditService() {
        val entries = mutableListOf<Pair<String, String>>()

        /** Separate from [entries] so the existing assertions on that list stay untouched. */
        val traceIds = mutableListOf<String?>()

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
            entries.add(operation to entityId)
            traceIds.add(traceId)
            return "AUDIT-REC"
        }
    }

    private class NoOpTransactionAuditService : TransactionAuditService {
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
        ): String = "AUDIT-NOOP"

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
