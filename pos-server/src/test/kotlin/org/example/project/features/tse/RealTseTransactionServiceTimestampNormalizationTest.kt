package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.features.audit.data.service.TransactionAuditServiceImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.RealTseTransactionServiceImpl
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Guards RA-3: `TSE_ZEITFORMAT` is declared `unixTime` (epoch seconds) in the DSFinV-K
 * export, but in [org.example.project.core.domain.enums.setup.TseMode.SIMULATION] the
 * TSE provider's Sign/Finish/Update results can hand back epoch MILLISECONDS
 * (`pos-tse`'s `BaseTseSignOperation.generateSimulatedSignResult` uses
 * `System.currentTimeMillis()` unmodified), while real NORMAL-mode hardware and the
 * simulated StartTransaction path already return epoch seconds.
 *
 * `RealTseTransactionServiceImpl` is the persistence seam: it must normalize any
 * TSE-provided timestamp to epoch seconds (via `toEpochSeconds`) before storing it as
 * `tseTimestamp` / `tseStartTimestamp`, regardless of which unit the provider handed back.
 */
@DisplayName("RealTseTransactionServiceImpl — TSE timestamp normalization (RA-3)")
class RealTseTransactionServiceTimestampNormalizationTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `SIMULATION-mode millis timestamp from a quick sign is normalized to epoch seconds before persisting`() {
        val millisTimestamp = 1_752_000_000_000L
        val expectedSeconds = 1_752_000_000L

        val service =
            signingService(FakeTseSignProvider(timestamp = millisTimestamp))

        val created =
            runBlocking {
                service.signTransaction(
                    processType = "Kassenbeleg-V1",
                    processData = "process-data",
                    referenceId = "RA3-1",
                    clientId = "TERM-RA3-1",
                    entityType = TseEntityType.BILL,
                )
            }
        assertNotNull(created, "signTransaction must persist a TseTransaction")

        val storedTseTimestamp =
            transaction {
                TseTransaction.findById(created.id.value)!!.tseTimestamp
            }

        assertEquals(
            expectedSeconds,
            storedTseTimestamp,
            "a millis-valued TSE timestamp must be normalized to epoch seconds before being persisted " +
                "as tseTimestamp, to match the declared TSE_ZEITFORMAT=unixTime",
        )
    }

    @Test
    fun `already-seconds timestamp from a quick sign passes through unchanged`() {
        val secondsTimestamp = 1_752_000_000L

        val service =
            signingService(FakeTseSignProvider(timestamp = secondsTimestamp))

        val created =
            runBlocking {
                service.signTransaction(
                    processType = "Kassenbeleg-V1",
                    processData = "process-data",
                    referenceId = "RA3-2",
                    clientId = "TERM-RA3-2",
                    entityType = TseEntityType.BILL,
                )
            }
        assertNotNull(created, "signTransaction must persist a TseTransaction")

        val storedTseTimestamp =
            transaction {
                TseTransaction.findById(created.id.value)!!.tseTimestamp
            }

        assertEquals(
            secondsTimestamp,
            storedTseTimestamp,
            "a real (already epoch-seconds) TSE timestamp must pass through unchanged",
        )
    }

    @Test
    fun `SIMULATION-mode millis timestamp from finishTransaction is normalized to epoch seconds`() {
        val millisTimestamp = 1_752_000_000_000L
        val expectedSeconds = 1_752_000_000L

        val service =
            signingService(FakeTseSignProvider(timestamp = millisTimestamp))

        val started =
            runBlocking {
                service.startTransaction(
                    processType = "Bestellung-V1",
                    processData = "start-process-data",
                    referenceId = null,
                    clientId = "TERM-RA3-3",
                )
            }
        assertNotNull(started, "startTransaction must persist a TseTransaction")

        val finished =
            runBlocking {
                service.finishTransaction(
                    tseTransactionId = started.id.value,
                    finalProcessData = "final-process-data",
                    clientId = "TERM-RA3-3",
                )
            }
        assertNotNull(finished, "finishTransaction must update the TseTransaction")

        val storedTseTimestamp =
            transaction {
                TseTransaction.findById(started.id.value)!!.tseTimestamp
            }

        assertEquals(
            expectedSeconds,
            storedTseTimestamp,
            "a millis-valued TSE FinishTransaction log time must be normalized to epoch seconds before " +
                "being persisted as tseTimestamp",
        )
    }

    private fun signingService(provider: TseSignProvider): RealTseTransactionServiceImpl {
        val repository = TseTransactionRepositoryImpl()
        return RealTseTransactionServiceImpl(
            repository,
            provider,
            TseVorgangSigner(provider, repository, TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json)),
        )
    }

    /**
     * Minimal fake that always answers with the same [timestamp] value, so tests can
     * control whether it looks like epoch millis or epoch seconds without depending on
     * `pos-tse`'s real SIMULATION-mode behavior.
     */
    private class FakeTseSignProvider(
        private val timestamp: Long,
    ) : TseSignProvider {
        // The real simulator does not answer with ONE serial: StartTransaction returns the plain
        // "SIM-TSE-001" while every sign/finish result returns it Base64-encoded (`pos-tse`'s
        // TseTransactionStartOperation vs BaseTseSignOperation). A fake using the plain form
        // everywhere hid a normalization defect: "SIM-TSE-001" is left alone by TseSerialFormat,
        // whereas the Base64 form was decoded, and then decoded a second time into a fabricated
        // serial that no simulated-signature check would have recognised.
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
                serialNumber = SIMULATED_SIGN_SERIAL,
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
                serialNumber = SIMULATED_SIGN_SERIAL,
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
                serialNumber = SIMULATED_SIGN_SERIAL,
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

        private companion object {
            /** Base64("SIM-TSE-001") — what `generateSimulatedSignResult` really returns. */
            const val SIMULATED_SIGN_SERIAL = "U0lNLVRTRS0wMDE="
        }
    }
}
