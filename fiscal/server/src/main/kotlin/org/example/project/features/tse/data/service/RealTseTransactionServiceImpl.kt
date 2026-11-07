/**
 * Production implementation of TSE Transaction Service for German fiscal compliance.
 *
 * Provides integration with real TSE (Technical Security Element) hardware
 * via the configured [TseSignProvider] (local or remote). All errors are
 * surfaced as [PosException] (B1 amendment) — see the cross-walk table in
 * `docs/superpowers/plans/2026-05-04-poserror-tse-migration.md`.
 *
 * Architecture:
 * - Main Server (this) → TseSignProvider → (Local|Remote) → TSE Hardware
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.models.TseTransactionState
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.tse.domain.repository.TseTransactionRepository
import org.example.project.features.tse.domain.service.TseTransactionService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.builder.ProcessDataBuilder
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.slf4j.LoggerFactory

class RealTseTransactionServiceImpl(
    private val repository: TseTransactionRepository,
    private val tseSignClient: TseSignProvider,
    private val signer: TseVorgangSigner,
) : TseTransactionService {
    private val logger = LoggerFactory.getLogger(RealTseTransactionServiceImpl::class.java)

    init {
        // Check TSE server connection on service startup
        checkTseServerConnection()
    }

    override suspend fun startTransaction(
        processType: String,
        processData: String,
        referenceId: String?,
        clientId: String,
    ): TseTransaction? {
        logger.info("Starting TSE transaction for $processType with reference ID: $referenceId")
        ensureTseReachable()

        val startTimestamp = currentTimeStamp()

        // Provider throws PosException on failure; let it propagate.
        val tseResponse = tseSignClient.startTransaction(processType, clientId)
        logger.info("TSE transaction started: txNum=${tseResponse.transactionNumber}")

        val mode = if (tseSignClient.getCachedMode() == "SIMULATION") TseMode.SIMULATION else TseMode.NORMAL
        return dbQuery {
            val created =
                repository.create(
                    tseSerialNumber =
                        org.example.project.utils.formatter.TseSerialFormat
                            .normalizeToHex(tseResponse.serialNumber),
                    transactionNumber = tseResponse.transactionNumber,
                    processType = processType,
                    processDataHash = processData,
                    signature = null,
                    startTime = startTimestamp,
                    clientId = tseResponse.clientId,
                    tseMode = mode,
                    // The TSE hardware's own StartTransaction log time (distinct from the server
                    // clock captured above) — used to source TSE_TA_START in the DSFinV-K export.
                    // Normalized to epoch seconds: RA-3, SIMULATION-mode TSE responses can report
                    // epoch millis while TSE_ZEITFORMAT declares unixTime (seconds).
                    tseStartTimestamp = toEpochSeconds(tseResponse.timestamp),
                )
            // m3: stamp the device's log-time format (TSE_ZEITFORMAT) from the in-memory cache —
            // no device I/O on the signing path. Null until status has been observed; the export
            // then falls back to "unixTime", correct for the Swissbit device in use.
            created.state = TseTransactionState.STARTED
            TseDeviceInfoCache.logTimeFormat?.let { created.logTimeFormat = it }
            logger.info("TSE transaction stored: ID=${created.id.value}, TxNum=${tseResponse.transactionNumber}")
            created
        }
    }

    /**
     * Finishes [tseTransactionId] with the canonical `AVBelegabbruch` payload — see
     * [TseTransactionService.abortTransaction] for the DSFinV-K rules and the export obligation
     * a future caller must satisfy first.
     */
    override suspend fun abortTransaction(
        tseTransactionId: Long,
        clientId: String,
    ): TseTransaction? {
        logger.info("Aborting TSE transaction as AVBelegabbruch: $tseTransactionId")
        return finishTransaction(
            tseTransactionId = tseTransactionId,
            finalProcessData = ProcessDataBuilder.buildBelegabbruch(),
            clientId = clientId,
        )
    }

    override suspend fun finishTransaction(
        tseTransactionId: Long,
        finalProcessData: String,
        clientId: String,
    ): TseTransaction? {
        logger.info("Finishing TSE transaction: $tseTransactionId")
        ensureTseReachable()

        data class Started(
            val transactionNumber: Long,
            val processType: String,
        )
        val started =
            dbQuery {
                repository.findById(tseTransactionId)?.let { row ->
                    // A row with no transaction number cannot be finished; fall through to the 404
                    // rather than raising an NPE the caller sees as a 500.
                    row.transactionNumber?.let { number ->
                        Started(number, row.processType ?: TseTransactionService.PROCESS_TYPE_RECEIPT)
                    }
                }
            } ?: throw transactionNotFound(tseTransactionId)

        val tseResponse =
            tseSignClient.finishTransaction(
                transactionNumber = started.transactionNumber,
                processData = finalProcessData,
                clientId = clientId,
                processType = started.processType,
            )
        logger.info(
            "TSE transaction finished: txNum=${started.transactionNumber}, sigCounter=${tseResponse.signatureCounter}",
        )

        return dbQuery {
            val updated =
                repository.update(
                    tseTransactionId = tseTransactionId,
                    processDataHash = finalProcessData,
                    signature = tseResponse.signature,
                    endTime = currentTimeStamp(),
                    exportedFileReference = null,
                    tseTimestamp = toEpochSeconds(tseResponse.timestamp),
                    signatureCounter = tseResponse.signatureCounter,
                    signatureAlgorithm = tseResponse.signatureAlgorithm,
                    publicKey = tseResponse.publicKey,
                    certificate = tseResponse.certificate,
                )
            updated?.state = TseTransactionState.FINISHED
            if (updated != null) {
                logger.info("TSE transaction updated: ID=${updated.id.value}, sigCounter=${tseResponse.signatureCounter}")
            } else {
                logger.error("Failed to update TSE transaction: $tseTransactionId not found after finish")
            }
            updated
        }
    }

    override suspend fun updateTransaction(
        tseTransactionId: Long,
        processData: String,
        clientId: String,
    ): TseTransaction? {
        logger.info("Updating TSE transaction: $tseTransactionId")
        ensureTseReachable()

        data class TxInfo(
            val transactionNumber: Long,
            val processType: String?,
        )
        val txInfo =
            dbQuery {
                repository.findById(tseTransactionId)?.let {
                    TxInfo(it.transactionNumber!!, it.processType)
                }
            } ?: throw transactionNotFound(tseTransactionId)

        if (txInfo.processType == TseTransactionService.PROCESS_TYPE_RECEIPT) {
            throw PosException(
                code = PosErrorCode.TSE_INVALID_PROCESS_TYPE,
                httpStatus = HttpStatusCode.BadRequest,
                message = "processType: Kassenbeleg-V1 transactions cannot be updated",
            )
        }

        val tseResponse =
            tseSignClient.updateTransaction(
                transactionNumber = txInfo.transactionNumber,
                processData = processData,
                clientId = clientId,
            )
        logger.info(
            "TSE transaction updated: txNum=${txInfo.transactionNumber}, sigCounter=${tseResponse.signatureCounter}",
        )

        return dbQuery {
            val updated =
                repository.update(
                    tseTransactionId = tseTransactionId,
                    processDataHash = processData,
                    signature = tseResponse.signature,
                    endTime = null,
                    exportedFileReference = null,
                    tseTimestamp = toEpochSeconds(tseResponse.timestamp),
                    signatureCounter = tseResponse.signatureCounter,
                    signatureAlgorithm = tseResponse.signatureAlgorithm,
                    publicKey = tseResponse.publicKey,
                    certificate = tseResponse.certificate,
                )
            if (updated != null) {
                logger.info("TSE transaction updated in DB: ID=${updated.id.value}")
            } else {
                logger.error("Failed to update TSE transaction in DB: $tseTransactionId disappeared mid-update")
            }
            updated
        }
    }

    override suspend fun signTransaction(
        processType: String,
        processData: String,
        referenceId: String,
        clientId: String,
        entityType: TseEntityType,
    ): TseTransaction? {
        logger.info("Signing TSE transaction: processType=$processType, $entityType $referenceId")
        ensureTseReachable()
        return signer.sign(TseSignRequest(referenceId, entityType, processType, processData, clientId))
    }

    override suspend fun getTseTransactionById(tseTransactionId: Long): TseTransaction =
        repository.findById(tseTransactionId)
            ?: throw transactionNotFound(tseTransactionId)

    override suspend fun getTseTransactionsByDateRange(
        startDate: String,
        endDate: String,
        limit: Int,
        offset: Int,
    ): List<TseTransaction> {
        logger.info("Retrieving TSE transactions for date range: $startDate to $endDate")
        // Behavior preserved from pre-migration code: calls findCompleted (not findByDateRange).
        val transactions = repository.findCompleted(limit, offset)
        logger.info("Retrieved ${transactions.size} TSE transactions")
        return transactions
    }

    override suspend fun validateTransaction(tseTransactionId: Long): Boolean {
        logger.info("Validating TSE transaction: $tseTransactionId")
        val transaction =
            repository.findById(tseTransactionId)
                ?: throw transactionNotFound(tseTransactionId)

        val hasSignature = transaction.signature?.isNotBlank() == true
        val hasProcessDataHash = transaction.processDataHash?.isNotBlank() == true
        val hasStartTime = transaction.startTime != null
        val hasEndTime = transaction.endTime != null
        val hasTransactionNumber = transaction.transactionNumber != null

        val isValid = hasSignature && hasProcessDataHash && hasStartTime && hasEndTime && hasTransactionNumber
        logger.info(
            "TSE transaction validation: valid=$isValid (sig=$hasSignature, hash=$hasProcessDataHash, " +
                "times=${hasStartTime && hasEndTime}, txNum=$hasTransactionNumber)",
        )
        return isValid
    }

    /**
     * Verifies the TSE provider is reachable; otherwise probes once and throws if still down.
     * Preserves pre-migration behavior of calling `checkHealth()` once before failing hard.
     */
    private suspend fun ensureTseReachable() {
        if (!tseSignClient.isConnected()) {
            tseSignClient.checkHealth()
            if (!tseSignClient.isConnected()) {
                logger.error("TSE server not reachable")
                throw PosException(
                    code = PosErrorCode.TSE_UNREACHABLE,
                    httpStatus = HttpStatusCode.ServiceUnavailable,
                    message = "TSE Server is unavailable",
                )
            }
        }
    }

    /**
     * Normalizes a TSE-provided timestamp to epoch seconds.
     *
     * Delegates to the shared [TseTimestampNormalizer] — see its kdoc (RA-3) for the
     * millis-vs-seconds heuristic. Kept as a thin wrapper so call sites in this class
     * don't need the `TseTimestampNormalizer.` prefix.
     */
    private fun toEpochSeconds(timestamp: Long?): Long? = TseTimestampNormalizer.toEpochSeconds(timestamp)

    private fun transactionNotFound(id: Long): PosException =
        PosException(
            code = PosErrorCode.TSE_TRANSACTION_NOT_FOUND,
            httpStatus = HttpStatusCode.NotFound,
            message = "TSE transaction $id not found",
        )

    /** Checks if the TSE server is reachable and updates connection state. */
    private fun checkTseServerConnection() {
        logger.info("Checking TSE server connection...")
        kotlinx.coroutines.runBlocking {
            tseSignClient.checkHealth()
            if (tseSignClient.isConnected()) {
                logger.info("TSE server connected: mode=${tseSignClient.getCachedMode()}")
            } else {
                logger.warn("TSE server not reachable")
            }
        }
    }
}
