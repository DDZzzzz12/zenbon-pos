package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.infrastructure.errors.PosException

/**
 * A TSE that behaves like the device for signing: it tracks which transactions are open and
 * which are finished, and can be told to fail an operation or to lose a finish reply after
 * the device has already signed. Public so any TSE test can use it.
 */
class ScriptedTseSignProvider(
    var mode: String = "NORMAL",
) : TseSignProvider {
    var nextTanr = 100L
    private var nextCounter = 1_000L

    /** Every call in order: "start", "finish:<tanr>:<processType>", "quickSign". */
    val calls = mutableListOf<String>()
    val openOnDevice = mutableSetOf<Long>()
    val finishedOnDevice = mutableSetOf<Long>()

    private val failures = mutableMapOf<String, ArrayDeque<Throwable>>()

    /** The next finish signs on the device, but the caller gets a timeout instead of the reply. */
    var loseNextFinishReply = false

    /**
     * The serial this fake reports from here on, for the device-swap checks: a replaced TSE answers
     * under its OWN serial (and restarts its transaction counter, which `nextTanr` models).
     */
    var serial: String = SERIAL_BASE64

    /**
     * Reports this serial on the NEXT finish reply only — the device answering is not the one the
     * transaction was opened on, which is what the till must detect after the fact.
     */
    var serialOnNextFinish: String? = null

    /**
     * Makes the NEXT finish reply carry this transaction number instead of the one asked for — the
     * shape of an answer that belongs to a different Vorgang.
     */
    var answerForTanr: Long? = null

    fun failNext(
        operation: String,
        error: Throwable,
    ) {
        failures.getOrPut(operation) { ArrayDeque() }.addLast(error)
    }

    private fun scriptedFailure(operation: String) {
        failures[operation]?.removeFirstOrNull()?.let { throw it }
    }

    override suspend fun startTransaction(
        processType: String,
        clientId: String,
    ): TseDto.TransactionStartResultDto {
        calls += "start"
        scriptedFailure("start")
        val tanr = nextTanr++
        openOnDevice += tanr
        return TseDto.TransactionStartResultDto(
            success = true,
            processType = processType,
            transactionNumber = tanr,
            timestamp = START_LOG_TIME,
            clientId = clientId,
            serialNumber = serial,
        )
    }

    override suspend fun finishTransaction(
        transactionNumber: Long,
        processData: String,
        clientId: String,
        processType: String,
    ): TseDto.SignResultDto {
        calls += "finish:$transactionNumber:$processType"
        scriptedFailure("finish")
        if (transactionNumber !in openOnDevice) {
            throw PosException(
                PosErrorCode.TSE_TRANSACTION_NOT_OPEN,
                HttpStatusCode.Conflict,
                "TSE transaction is not open: WORM_ERROR_TRANSACTION_NOT_STARTED",
            )
        }
        openOnDevice -= transactionNumber
        finishedOnDevice += transactionNumber
        if (loseNextFinishReply) {
            loseNextFinishReply = false
            throw PosException(
                PosErrorCode.TSE_COMMUNICATION_TIMEOUT,
                HttpStatusCode.ServiceUnavailable,
                "TSE did not answer: WORM_ERROR_TIMEOUT",
            )
        }
        val answeringSerial = serialOnNextFinish?.also { serialOnNextFinish = null } ?: serial
        val answeringTanr = answerForTanr?.also { answerForTanr = null } ?: transactionNumber
        return signed(answeringTanr, processType, startTimestamp = null, serialNumber = answeringSerial)
    }

    override suspend fun quickSign(
        processType: String,
        processData: String,
        clientId: String,
    ): TseDto.SignResultDto {
        calls += "quickSign"
        scriptedFailure("quickSign")
        val tanr = nextTanr++
        finishedOnDevice += tanr
        return signed(tanr, processType, startTimestamp = START_LOG_TIME)
    }

    private fun signed(
        tanr: Long,
        processType: String,
        startTimestamp: Long?,
        serialNumber: String = serial,
    ) = TseDto.SignResultDto(
        success = true,
        processType = processType,
        transactionNumber = tanr,
        signature = "c2lnbmF0dXJl",
        timestamp = FINISH_LOG_TIME,
        serialNumber = serialNumber,
        signatureCounter = nextCounter++,
        signatureAlgorithm = "ecdsa-plain-SHA384",
        publicKey = "cHVibGlj",
        certificate = "Y2VydA==",
        startTimestamp = startTimestamp,
    )

    override suspend fun updateTransaction(
        transactionNumber: Long,
        processData: String,
        clientId: String,
    ): TseDto.SignResultDto = throw UnsupportedOperationException()

    override suspend fun checkHealth(): TseDto.HealthCheckResultDto =
        TseDto.HealthCheckResultDto(
            healthy = true,
            initialized = true,
            validTime = true,
            passedSelfTest = true,
            remainingSignatures = 1_000_000L,
            availableCapacity = 1_000_000L,
        )

    override fun isConnected(): Boolean = true

    override fun getCachedMode(): String = mode

    override fun registerConnectionListener() {}

    override suspend fun restartConnection(): TseDto.HealthCheckResultDto? = null

    override fun close() {}

    companion object {
        /** A real 32-byte serial in the form the device delivers it, and its hex form (E2E R2-5). */
        const val SERIAL_BASE64 = "LS+I+8MLvJrOt1wo4hIQWZZ4bHoKCLLGz3Qwbtx17Eg="
        const val SERIAL_HEX = "2D2F88FBC30BBC9ACEB75C28E212105996786C7A0A08B2C6CF74306EDC75EC48"

        /** A second device's serial, for the swap/foreign-answer checks. */
        const val REPLACEMENT_SERIAL_BASE64 = "3WdmCLLGz3Qwbtx17EgtL4j7wwu8ms63XCjiEhBZlng="
        const val REPLACEMENT_SERIAL_HEX = "DD676608B2C6CF74306EDC75EC482D2F88FBC30BBC9ACEB75C28E21210599678"

        const val START_LOG_TIME = 1_790_000_000L
        const val FINISH_LOG_TIME = 1_790_000_001L
    }
}
