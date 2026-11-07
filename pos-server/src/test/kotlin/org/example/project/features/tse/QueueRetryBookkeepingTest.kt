package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.tse.data.repository.TseSigningQueueRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.domain.models.TseSigningQueue
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * B1, second half: `processEntry` caught only [org.example.project.infrastructure.errors.PosException].
 *
 * A NON-PosException raised while persisting the signature — the `ExposedSQLException` the
 * over-long `process_data_hash` used to produce — escaped the OUTER `dbQuery`, taking
 * `markAsProcessing` and the retry bookkeeping down with it in the rollback. `retryCount` never
 * incremented and the entry stayed PENDING, so `findReadyForProcessing` handed the worker the
 * same entry on the next cycle: a successful `quickSign` on the TSE every
 * `RETRY_INTERVAL_SECONDS`, discarded each time, forever.
 *
 * That burns the device's finite signature budget and never raises an alarm, because the entry
 * can never reach FAILED. Widening the column (first half) removes today's trigger; this guard
 * makes any future persistence failure self-limiting instead of unbounded.
 */
@DisplayName("Queue retry bookkeeping survives a non-PosException (B1)")
class QueueRetryBookkeepingTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    @Test
    fun `a non-PosException increments retryCount instead of rolling it back`() {
        val service =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                ExplodingSignProvider(),
                NoOpTransactionAuditService(),
            )

        val entryId =
            runBlocking {
                service
                    .enqueue(
                        referenceId = "42",
                        entityType = TseEntityType.ORDER,
                        processType = "Bestellung-V1",
                        processData = "1;\"Eisbecher\";3.99",
                        clientId = "TERM-B1",
                        reason = TseLateSigningReason.TSE_UNAVAILABLE,
                        reasonText = "test",
                    ).id
            }

        // Must not propagate: the worker has to keep draining the rest of the batch.
        runCatching { runBlocking { service.processEntry(entryId) } }

        transaction {
            val entry = TseSigningQueue.findById(entryId)!!
            assertEquals(
                1,
                entry.retryCount,
                "a failed attempt must be COUNTED — if the bookkeeping rolls back with the " +
                    "exception, the worker re-signs the same payload on the TSE every cycle forever",
            )
            assertNotEquals(
                "PENDING",
                entry.signingStatus.name,
                "the entry must leave PENDING, or findReadyForProcessing hands it straight back",
            )
        }
    }

    /** Stands in for the ExposedSQLException the over-long payload used to raise on persist. */
    private class ExplodingSignProvider : TseSignProvider {
        override suspend fun quickSign(
            processType: String,
            processData: String,
            clientId: String,
        ): TseDto.SignResultDto = throw IllegalStateException("persist failed after the TSE signed")

        override suspend fun startTransaction(
            processType: String,
            clientId: String,
        ): TseDto.TransactionStartResultDto = throw UnsupportedOperationException()

        override suspend fun updateTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
        ): TseDto.SignResultDto = throw UnsupportedOperationException()

        override suspend fun finishTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
            processType: String,
        ): TseDto.SignResultDto = throw UnsupportedOperationException()

        override suspend fun checkHealth(): TseDto.HealthCheckResultDto? = null

        override fun isConnected(): Boolean = true

        override fun getCachedMode(): String = "SIMULATION"

        override fun registerConnectionListener() = Unit

        override suspend fun restartConnection(): TseDto.HealthCheckResultDto? = null

        override fun close() = Unit
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
