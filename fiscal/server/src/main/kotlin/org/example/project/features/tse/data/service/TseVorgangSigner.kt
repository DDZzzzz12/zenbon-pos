package org.example.project.features.tse.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.domain.enums.tse.TseExplanationSource
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.models.TseTransactionState
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.tse.domain.repository.TseTransactionRepository
import org.example.project.features.tse.findings.TseExplanationRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.TseSerialFormat
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.serialHex
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory

/**
 * The only code that writes a Vorgang's `tse_transaction` row (E2E R2-7, E1, E2; design
 * 2026-09-22 §4.1).
 *
 * NORMAL mode: start → commit STARTED → finish → commit FINISHED, with no database transaction
 * open while the device works. A retry finds the STARTED row by ([TseSignRequest.referenceId],
 * [TseSignRequest.entityType]) and finishes the same TANR, so a failed write no longer throws a
 * signature away. If the device has already closed that TANR, the row is SUPERSEDED and the
 * Vorgang is signed once more under a new TANR, which is the retry TR-03153-1 §9.4 prescribes.
 *
 * A stored TANR is only ever finished on a device we can identify AS the one that opened it. A
 * replacement TSE restarts its counter at 1, so a stored number is very likely OPEN on a new
 * device, belonging to somebody else's Vorgang; finishing it would write this Vorgang's process
 * data into that transaction, permanently, in write-once memory. Before finishing a RESUMED row the
 * signer therefore requires [TseDeviceInfoCache.lastSeenSerialHex] to be known AND to match the
 * row: an unknown identity (nothing has identified the device since this process started) is
 * treated as unsafe, not as permission. The cost is one superseded, explained TANR after a restart
 * in which nothing read the device; `LocalTseAdminProvider.getInfo` — the status screen's own call
 * — fills the cache without signing, so the ordinary restart still resumes on the original TANR.
 *
 * After the finish returns, the answer is checked against the row again (number and serial). That
 * is detection, not prevention: by then the device has already closed whatever transaction it
 * closed, and all the signer can still do is refuse to STORE a foreign signature and sign the
 * Vorgang again under a new number.
 *
 * SIMULATION mode quick-signs in one step: a simulated start numbers transactions by epoch
 * second, so two sales in one second would collide on (serial, TANR). Simulation rows carry no
 * fiscal weight.
 */
class TseVorgangSigner(
    private val provider: TseSignProvider,
    private val repository: TseTransactionRepository,
    private val auditService: TransactionAuditService,
    private val explanations: TseExplanationRepository = TseExplanationRepository(),
) {
    private val logger = LoggerFactory.getLogger(TseVorgangSigner::class.java)

    companion object {
        /** Audit operation for a TANR the device closed without the till recording its finish. */
        const val TSE_TRANSACTION_SUPERSEDED = "TSE_TRANSACTION_SUPERSEDED"
    }

    suspend fun sign(request: TseSignRequest): TseTransaction {
        // A PosException, not check()/IllegalStateException: no call site catches the latter, so a
        // future regression would escape as a 500 AFTER the sale committed instead of taking the
        // "queue for Nachsignierung" path every caller already has for TSE_SIGNING_FAILED.
        if (TransactionManager.currentOrNull() != null) {
            throw PosException(
                code = PosErrorCode.TSE_SIGNING_FAILED,
                httpStatus = HttpStatusCode.ServiceUnavailable,
                message =
                    "TSE signing must not run inside a database transaction: the signature would only " +
                        "persist if the whole transaction commits (E2E R2-7)",
            )
        }
        val current =
            persisting {
                repository.findCurrentByReference(request.referenceId, request.entityType.name)
                    ?: repository.findLegacySigned(request.referenceId, request.processType, request.processData)
            }
        if (current != null && current.state == TseTransactionState.FINISHED) {
            logger.warn("TSE: ${request.entityType} ${request.referenceId} already signed (TANR ${current.transactionNumber}); adopting it")
            return current
        }
        if (provider.getCachedMode() == "SIMULATION") {
            // A NORMAL→SIMULATION switch can leave a STARTED row behind. Quick-signing would then
            // add a SECOND live row for this Vorgang, which `tse_transaction_one_live_per_vorgang`
            // refuses on PostgreSQL — failing every retry forever. The stranded row is superseded
            // first: its TANR stays on file and is exported with its explanation.
            if (current != null) supersede(request, current, "TSE switched to SIMULATION mode before this TANR was finished")
            return signSimulated(request)
        }
        return if (current != null) {
            finishOrSupersede(request, current, allowRestart = true, resumed = true)
        } else {
            startAndFinish(request, allowRestart = true)
        }
    }

    private suspend fun startAndFinish(
        request: TseSignRequest,
        allowRestart: Boolean,
    ): TseTransaction {
        val start = provider.startTransaction(request.processType, request.clientId)
        TseDeviceInfoCache.recordSerial(start.serialNumber)
        val started =
            persisting {
                repository
                    .create(
                        tseSerialNumber = TseSerialFormat.normalizeToHex(start.serialNumber),
                        transactionNumber = start.transactionNumber,
                        processType = request.processType,
                        processDataHash = null,
                        signature = null,
                        startTime = currentTimeStamp(),
                        clientId = request.clientId,
                        tseMode = TseMode.NORMAL,
                        tseStartTimestamp = TseTimestampNormalizer.toEpochSeconds(start.timestamp),
                        referenceId = request.referenceId,
                        entityType = request.entityType.name,
                    ).also { created ->
                        created.state = TseTransactionState.STARTED
                        TseDeviceInfoCache.logTimeFormat?.let { created.logTimeFormat = it }
                    }
            }
        logger.info("TSE: ${request.entityType} ${request.referenceId} started as TANR ${start.transactionNumber}")
        // Not `resumed`: this call opened the transaction on the attached device a moment ago, so
        // the device it must be finished on is known by construction.
        return finishOrSupersede(request, started, allowRestart, resumed = false)
    }

    private suspend fun finishOrSupersede(
        request: TseSignRequest,
        started: TseTransaction,
        allowRestart: Boolean,
        resumed: Boolean,
    ): TseTransaction {
        val tanr = requireNotNull(started.transactionNumber) { "STARTED TSE transaction ${started.id.value} has no TANR" }

        // PREVENT (fix A): a stored TANR belongs to the device that issued it, and a RESUMED row is
        // only safe to finish on a device we can identify as that one. Both "a different device is
        // attached" and "no device identity is known yet" take the supersede-and-resign path:
        // starting a fresh transaction on the attached device is always safe, while finishing a
        // stored number on an unknown device can permanently close a stranger's transaction.
        unsafeToFinishReason(started, resumed)?.let { reason ->
            logger.error("TSE: $reason")
            return supersedeAndResign(request, started, reason, allowRestart, notOpen(reason))
        }

        val result =
            try {
                provider.finishTransaction(tanr, request.processData, request.clientId, request.processType)
            } catch (e: PosException) {
                if (e.code != PosErrorCode.TSE_TRANSACTION_NOT_OPEN) throw e
                return supersedeAndResign(request, started, e.message ?: e.code.name, allowRestart, e)
            }
        TseDeviceInfoCache.recordSerial(result.serialNumber)

        // DETECT (fix A): the answer must come from the device the row names, for the number we
        // asked to close. Writing a foreign signature onto this row would attribute one device's
        // signature to another's serial — unverifiable for an auditor, and it would hide that some
        // other Vorgang's transaction was just closed with our data.
        foreignAnswerReason(started, tanr, result)?.let { reason ->
            logger.error("TSE: $reason")
            return supersedeAndResign(request, started, reason, allowRestart, notOpen(reason))
        }

        return persisting {
            val finished =
                repository.update(
                    tseTransactionId = started.id.value,
                    processDataHash = request.processData,
                    signature = result.signature,
                    endTime = currentTimeStamp(),
                    tseTimestamp = TseTimestampNormalizer.toEpochSeconds(result.timestamp),
                    signatureCounter = result.signatureCounter,
                    signatureAlgorithm = result.signatureAlgorithm,
                    publicKey = result.publicKey,
                    certificate = result.certificate,
                ) ?: error("TSE transaction ${started.id.value} vanished before its finish was recorded")
            finished.state = TseTransactionState.FINISHED
            finished
        }
    }

    /**
     * Why [started]'s transaction number must NOT be handed to the attached device, or null when
     * finishing it is safe.
     *
     * Only [resumed] rows are at risk: a row this call just started was opened on the attached
     * device moments ago. For a resumed one the device must be IDENTIFIED — an unknown identity is
     * the likeliest shape of the very failure this guards (the TSE died mid-signing, was swapped,
     * and the server restarted), so it is refused rather than assumed benign. A row that names no
     * device at all cannot be checked either way and is refused for the same reason.
     */
    private fun unsafeToFinishReason(
        started: TseTransaction,
        resumed: Boolean,
    ): String? {
        if (!resumed) return null
        // serialHex(), not the raw column: a row written before V82 may hold the device's Base64
        // serial, and comparing that against the cache's hex would read as "a different device".
        val opened =
            started.serialHex()
                ?: return "TANR ${started.transactionNumber} records no TSE serial, so the device that opened it is unknown"
        val attached =
            TseDeviceInfoCache.lastSeenSerialHex
                ?: return "device identity unknown after restart: TANR ${started.transactionNumber} was opened on TSE " +
                    "$opened and nothing has identified the attached device this run"
        if (attached.equals(opened, ignoreCase = true)) return null
        return "TANR ${started.transactionNumber} was opened on TSE $opened but TSE $attached is attached now; " +
            "finishing it could close another Vorgang's transaction"
    }

    /** The finish reply does not answer for [tanr] on [started]'s device — see the call site. */
    private fun foreignAnswerReason(
        started: TseTransaction,
        tanr: Long,
        result: TseDto.SignResultDto,
    ): String? {
        if (result.transactionNumber != tanr) {
            return "TSE answered for TANR ${result.transactionNumber} but TANR $tanr was asked to finish"
        }
        val answered = TseSerialFormat.normalizeToHex(result.serialNumber)
        val opened = started.serialHex()
        if (opened != null && answered != null && !answered.equals(opened, ignoreCase = true)) {
            return "TANR $tanr was opened on TSE $opened but the finish was answered by TSE $answered"
        }
        return null
    }

    private fun notOpen(reason: String) = PosException(PosErrorCode.TSE_TRANSACTION_NOT_OPEN, HttpStatusCode.Conflict, reason)

    /**
     * Supersedes [started] and signs the Vorgang once more under a new TANR on the current device
     * — the retry TR-03153-1 §9.4 prescribes. [cause] is what the caller gets when a restart is not
     * allowed (a replacement that failed the same way), so the failure is never swallowed.
     */
    private suspend fun supersedeAndResign(
        request: TseSignRequest,
        started: TseTransaction,
        reason: String,
        allowRestart: Boolean,
        cause: PosException,
    ): TseTransaction {
        supersede(request, started, reason)
        if (!allowRestart) throw cause
        val replacement = startAndFinish(request, allowRestart = false)
        persisting { repository.linkReplacement(started.id.value, replacement.id.value) }
        return replacement
    }

    private suspend fun supersede(
        request: TseSignRequest,
        started: TseTransaction,
        reason: String,
    ) {
        persisting {
            repository.markSuperseded(started.id.value)
            // The superseded row stays in `tse_transaction`, so the continuity analyzer never sees
            // this TANR as a gap. The entry is written for the register itself: it records, at the
            // moment it happened and without an operator having to reconstruct it later, WHY this
            // number carries no completed Vorgang — evidence for the Verfahrensdokumentation and
            // for an auditor reading the TSE export beside our records (AEAO § 146a Nr. 1.15.1,
            // Swissbit AGD §5.2). Row and explanation land in one transaction, so neither can
            // exist alone.
            val serial = started.serialHex()
            val tanr = started.transactionNumber
            if (serial != null && tanr != null) {
                explanations.insert(
                    serialHex = serial,
                    tanrFrom = tanr,
                    tanrTo = tanr,
                    category = TseExplanationCategory.SUPERSEDED,
                    text =
                        "TSE-Transaktion $tanr abgeschlossen, Antwort nicht erhalten; " +
                            "der Vorgang wurde unter neuer TANR signiert (BSI TR-03153-1 §9.4).",
                    source = TseExplanationSource.SYSTEM,
                    employeeId = null,
                    supersedesId = null,
                )
            }
        }
        logger.warn(
            "TSE: TANR ${started.transactionNumber} of ${request.entityType} ${request.referenceId} " +
                "cannot be finished as recorded; superseding it ($reason)",
        )
        try {
            auditService.logTransaction(
                employeeId = "SYSTEM",
                employeeNumber = "SYSTEM",
                authorizationType = "AUTOMATIC",
                authorizingManagerId = "SYSTEM",
                operation = TSE_TRANSACTION_SUPERSEDED,
                entityType = request.entityType.name,
                entityId = request.referenceId,
                entityName = "TSE-Transaktion ${started.transactionNumber} ersetzt",
                deviceId = request.clientId,
                reason = "TSE transaction ${started.transactionNumber} could not be finished as recorded: $reason",
                fiscalRecordId = started.id.value.toString(),
                metadata =
                    mapOf(
                        "tseTransactionNumber" to started.transactionNumber.toString(),
                        // Which device opened the TANR is identity, not display — the canonical form.
                        "tseSerialNumber" to (started.serialHex() ?: ""),
                    ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Audit log FAILED for $TSE_TRANSACTION_SUPERSEDED ${request.entityType} ${request.referenceId}", e)
        }
    }

    private suspend fun signSimulated(request: TseSignRequest): TseTransaction {
        val result = provider.quickSign(request.processType, request.processData, request.clientId)
        return persisting {
            val created =
                repository.create(
                    tseSerialNumber = TseSerialFormat.normalizeToHex(result.serialNumber),
                    transactionNumber = result.transactionNumber,
                    processType = request.processType,
                    processDataHash = request.processData,
                    signature = result.signature,
                    startTime = currentTimeStamp(),
                    clientId = request.clientId,
                    tseMode = TseMode.SIMULATION,
                    tseStartTimestamp = TseTimestampNormalizer.toEpochSeconds(result.startTimestamp),
                    referenceId = request.referenceId,
                    entityType = request.entityType.name,
                )
            repository.update(
                tseTransactionId = created.id.value,
                processDataHash = request.processData,
                signature = result.signature,
                endTime = currentTimeStamp(),
                tseTimestamp = TseTimestampNormalizer.toEpochSeconds(result.timestamp),
                signatureCounter = result.signatureCounter,
                signatureAlgorithm = result.signatureAlgorithm,
                publicKey = result.publicKey,
                certificate = result.certificate,
            )
            created.state = TseTransactionState.FINISHED
            TseDeviceInfoCache.logTimeFormat?.let { created.logTimeFormat = it }
            created
        }
    }

    /**
     * Runs [block] in its own short transaction. A database failure becomes TSE_SIGNING_FAILED, so
     * every call site takes its existing "queue for Nachsignierung" path instead of escaping as a
     * 500 after the device signed (E2).
     */
    private suspend fun <T> persisting(block: suspend () -> T): T =
        try {
            dbQuery { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            throw PosException(
                code = PosErrorCode.TSE_SIGNING_FAILED,
                httpStatus = HttpStatusCode.ServiceUnavailable,
                message = "TSE signing could not be recorded: ${e.message}",
                cause = e,
            )
        }
}
