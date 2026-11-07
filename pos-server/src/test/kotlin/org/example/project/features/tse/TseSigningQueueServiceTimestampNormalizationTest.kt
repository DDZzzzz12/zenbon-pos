package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.tse.data.repository.TseSigningQueueRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.infrastructure.di.httpClientModule
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.di.servicesModule
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Guards A3-5 (DSFinV-K compliance remediation, audit #3): the Nachsignieren
 * ("late signing") queue worker persisted `tseTimestamp = signResultDto.timestamp`
 * directly in its own signing helper (now [org.example.project.features.tse.data.service.TseVorgangSigner]), without the
 * `toEpochSeconds` normalization that [org.example.project.features.tse.data.service.RealTseTransactionServiceImpl]
 * applies at its write sites (see [RealTseTransactionServiceTimestampNormalizationTest], RA-3).
 *
 * A queue entry re-signed via `quickSign` in SIMULATION mode can hand back an epoch-millis
 * timestamp; without normalization that gets persisted as `tseTimestamp`, producing
 * `TSE_TA_ENDE` in millis while `TSE_ZEITFORMAT` declares `unixTime` (seconds).
 *
 * Both write sites now delegate to the shared `TseTimestampNormalizer.toEpochSeconds`.
 */
@DisplayName("TseSigningQueueServiceImpl — TSE timestamp normalization (A3-5)")
class TseSigningQueueServiceTimestampNormalizationTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        startKoin {
            modules(serializationModule, httpClientModule, reposModule, servicesModule)
        }
    }

    @AfterEach
    fun teardown() {
        GlobalContext.stopKoin()
        TestDatabase.teardown()
    }

    @Test
    fun `SIMULATION-mode millis timestamp from the queue worker is normalized to epoch seconds before persisting`() {
        val millisTimestamp = 1_752_000_000_000L
        val expectedSeconds = 1_752_000_000L

        val service =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                FakeTseSignProvider(timestamp = millisTimestamp),
                NoOpTransactionAuditService(),
            )

        val entry =
            runBlocking {
                service.enqueue(
                    referenceId = "9001",
                    entityType = TseEntityType.ORDER,
                    processType = "Bestellung-V1",
                    processData = "process-data",
                    clientId = "TERM-A3-5-1",
                    reason = TseLateSigningReason.TSE_UNAVAILABLE,
                    reasonText = "test",
                )
            }

        val processed = runBlocking { service.processEntry(entry.id) }
        assertEquals(TseSigningStatus.SIGNED, processed.signingStatus, "queue entry must sign successfully")
        assertNotNull(processed.tseTransactionId, "signed entry must reference a TseTransaction")

        val storedTseTimestamp =
            transaction {
                TseTransaction.findById(processed.tseTransactionId!!)!!.tseTimestamp
            }

        assertEquals(
            expectedSeconds,
            storedTseTimestamp,
            "a millis-valued TSE timestamp from the queue worker must be normalized to epoch seconds " +
                "before being persisted as tseTimestamp, to match the declared TSE_ZEITFORMAT=unixTime",
        )
    }

    @Test
    fun `already-seconds timestamp from the queue worker passes through unchanged`() {
        val secondsTimestamp = 1_752_000_000L

        val service =
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                FakeTseSignProvider(timestamp = secondsTimestamp),
                NoOpTransactionAuditService(),
            )

        val entry =
            runBlocking {
                service.enqueue(
                    referenceId = "9002",
                    entityType = TseEntityType.ORDER,
                    processType = "Bestellung-V1",
                    processData = "process-data",
                    clientId = "TERM-A3-5-2",
                    reason = TseLateSigningReason.TSE_UNAVAILABLE,
                    reasonText = "test",
                )
            }

        val processed = runBlocking { service.processEntry(entry.id) }
        assertEquals(TseSigningStatus.SIGNED, processed.signingStatus, "queue entry must sign successfully")
        assertNotNull(processed.tseTransactionId, "signed entry must reference a TseTransaction")

        val storedTseTimestamp =
            transaction {
                TseTransaction.findById(processed.tseTransactionId!!)!!.tseTimestamp
            }

        assertEquals(
            secondsTimestamp,
            storedTseTimestamp,
            "a real (already epoch-seconds) TSE timestamp must pass through unchanged",
        )
    }

    /**
     * Minimal fake that always answers `quickSign` with the same [timestamp] value, so
     * tests can control whether it looks like epoch millis or epoch seconds.
     */
    private class FakeTseSignProvider(
        private val timestamp: Long,
    ) : TseSignProvider {
        override suspend fun quickSign(
            processType: String,
            processData: String,
            clientId: String,
        ): TseDto.SignResultDto =
            TseDto.SignResultDto(
                success = true,
                processType = processType,
                transactionNumber = 1L,
                signature = "SIM-SIGNATURE",
                timestamp = timestamp,
                serialNumber = "SIM-TSE-001",
                signatureCounter = 1L,
                signatureAlgorithm = "ecdsa-plain-SHA384",
                publicKey = null,
                certificate = null,
            )

        override suspend fun startTransaction(
            processType: String,
            clientId: String,
        ): TseDto.TransactionStartResultDto =
            TseDto.TransactionStartResultDto(
                success = true,
                processType = processType,
                transactionNumber = 1L,
                timestamp = timestamp,
                clientId = clientId,
                serialNumber = "SIM-TSE-001",
            )

        override suspend fun updateTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
        ): TseDto.SignResultDto =
            TseDto.SignResultDto(
                success = true,
                processType = "Bestellung-V1",
                transactionNumber = transactionNumber,
                signature = "SIM-SIGNATURE",
                timestamp = timestamp,
                serialNumber = "SIM-TSE-001",
                signatureCounter = 1L,
                signatureAlgorithm = "ecdsa-plain-SHA384",
                publicKey = null,
                certificate = null,
            )

        override suspend fun finishTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
            processType: String,
        ): TseDto.SignResultDto =
            TseDto.SignResultDto(
                success = true,
                processType = "Bestellung-V1",
                transactionNumber = transactionNumber,
                signature = "SIM-SIGNATURE",
                timestamp = timestamp,
                serialNumber = "SIM-TSE-001",
                signatureCounter = 1L,
                signatureAlgorithm = "ecdsa-plain-SHA384",
                publicKey = null,
                certificate = null,
            )

        override suspend fun checkHealth(): TseDto.HealthCheckResultDto =
            TseDto.HealthCheckResultDto(
                healthy = true,
                initialized = true,
                validTime = true,
                passedSelfTest = true,
                remainingSignatures = 1_000L,
                availableCapacity = 1_000L,
            )

        override fun isConnected(): Boolean = true

        override fun getCachedMode(): String = "SIMULATION"

        override fun registerConnectionListener() = Unit

        override suspend fun restartConnection(): TseDto.HealthCheckResultDto? = null

        override fun close() = Unit
    }

    /** Minimal fake that no-ops `logTransaction` (the only method the queue worker calls). */
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
