/**
 * Implementation of TseSigningQueueService for queue-based TSE signing.
 *
 * Provides business logic for the "Nachsignieren" (late signing) system
 * enabling German KassenSichV compliance with business continuity during
 * TSE outages.
 *
 * All errors surface as [PosException] (B1 amendment); see the cross-walk
 * table in `docs/superpowers/plans/2026-05-04-poserror-tse-migration.md`.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.tse.data.repository.TseLateSigningRepositoryImpl
import org.example.project.features.tse.domain.dto.toQueueEntryResponseDto
import org.example.project.features.tse.domain.models.TseSigningQueue
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.tse.domain.repository.TseLateSigningRepository
import org.example.project.features.tse.domain.repository.TseSigningQueueRepository
import org.example.project.features.tse.domain.repository.TseTransactionRepository
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.TRACE_ID_MDC_KEY
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.example.project.features.tse.domain.dto.TseSigningQueueDto as ServerQueueDto

@OptIn(ExperimentalTime::class)
class TseSigningQueueServiceImpl(
    private val queueRepository: TseSigningQueueRepository,
    private val tseTransactionRepository: TseTransactionRepository,
    private val tseSignClient: TseSignProvider,
    private val transactionAuditService: TransactionAuditService,
    private val lateSigningRepository: TseLateSigningRepository = TseLateSigningRepositoryImpl(),
    private val signer: TseVorgangSigner = TseVorgangSigner(tseSignClient, tseTransactionRepository, transactionAuditService),
) : TseSigningQueueService {
    private val logger = LoggerFactory.getLogger(TseSigningQueueServiceImpl::class.java)

    companion object {
        /**
         * M7: audit operation recorded when a TSE response was LOST after the request was sent.
         *
         * The device may already hold a signature for the payload, and nothing was persisted
         * locally, so the outcome is unknowable from here. We still retry — a Vorgang must not stay
         * unsigned — but the possible duplicate must be EXPLAINABLE to an auditor comparing the TSE
         * TAR export against the DSFinV-K data, rather than sitting unexplained in the WORM.
         */
        const val TSE_POSSIBLE_DOUBLE_SIGN = "TSE_POSSIBLE_DOUBLE_SIGN"

        /** M8: audit operation recorded when an operator abandons a Vorgang's Nachsignierung. */
        const val TSE_QUEUE_CANCELLED = "TSE_QUEUE_CANCELLED"

        /** An error meaning the device itself is unavailable: signing the rest of the batch would fail too, and overtake. */
        private val BATCH_STOP_CODES =
            setOf(
                PosErrorCode.TSE_CONNECTION_FAILED.name,
                PosErrorCode.TSE_COMMUNICATION_TIMEOUT.name,
                PosErrorCode.TSE_UNREACHABLE.name,
                PosErrorCode.TSE_HARDWARE_NOT_FOUND.name,
                PosErrorCode.TSE_INVALID_MOUNT_POINT.name,
                PosErrorCode.TSE_NOT_OPERATIONAL.name,
            )
    }

    /*
     * Trace ids on this service's audit rows, by entry point:
     *
     *  - [retryEntry] and [cancelEntry] are REQUEST-BACKED — their only callers are
     *    `TseSigningQueueRoutes`, driven by the operator's queue screen — so each captures
     *    `MDC.get(TRACE_ID_MDC_KEY)` at its own top, before any dbQuery, and threads it down.
     *    That is the ONLY place MDC is read in this file.
     *  - [processBatch], the sweeper, passes an explicit null.
     *  - [processEntry]'s public one-arg form also passes an explicit null. It has no caller in
     *    pos-server today; both production paths use the private overload. Reading MDC there would
     *    hand a future background caller whatever the pooled thread happened to hold, which is the
     *    hazard [processBatch]'s own comment calls worse than none — so the capture stays in the two
     *    methods that can justify it, and this form is honest about having no request behind it.
     */

    override suspend fun enqueue(
        referenceId: String,
        entityType: TseEntityType,
        processType: String,
        processData: String,
        clientId: String,
        reason: TseLateSigningReason,
        reasonText: String,
        openTseTransactionNumber: Long?,
    ): TseSigningQueueDto.QueueEntryResponseDto =
        dbQuery {
            logger.info("Enqueueing $entityType $referenceId for TSE signing")

            // Single unbypassable point: no future caller can queue safe cash for signing by
            // writing its own query. Reads is_fiscal directly from the cash book — the source
            // of truth — not the stored NOT_APPLICABLE status, which is a consequence of it.
            if (entityType == TseEntityType.KASSENBUCH) {
                val header = referenceId.toLongOrNull()?.let { KassenbuchHeader.findById(it) }
                require(header == null || header.cashBook.isFiscal) {
                    "Refusing to enqueue kassenbuch entry $referenceId: cash book " +
                        "'${header?.cashBook?.name}' is non-fiscal"
                }
            }

            // The Vorgang's permanent Nachsignierung record: why and since when. First queueing wins,
            // so a retry or the pending sweeper never overwrites the original cause.
            lateSigningRepository.recordQueued(entityType, referenceId, currentTimeStamp(), reason, reasonText)

            // Check if already queued
            val existing = queueRepository.findByReference(referenceId, entityType)
            if (existing != null && !existing.signingStatus.isTerminal()) {
                logger.warn("$entityType $referenceId already queued (ID=${existing.id.value})")
                return@dbQuery existing.toQueueEntryResponseDto()
            }

            val priority = entityType.getSigningPriority()
            val entry =
                queueRepository.create(
                    referenceId = referenceId,
                    entityType = entityType,
                    processType = processType,
                    processData = processData,
                    clientId = clientId,
                    openTseTransactionNumber = openTseTransactionNumber,
                    priority = priority,
                )
            logger.info("Queue entry created: ID=${entry.id.value}, $entityType $referenceId")

            updateEntitySigningStatus(referenceId, entityType, TseSigningStatus.PENDING)

            entry.toQueueEntryResponseDto()
        }

    /** No request is assumed behind this form; see the entry-point note above. */
    override suspend fun processEntry(queueEntryId: Long): TseSigningQueueDto.QueueEntryResponseDto = processEntry(queueEntryId, traceId = null)

    private suspend fun processEntry(
        queueEntryId: Long,
        traceId: String?,
    ): TseSigningQueueDto.QueueEntryResponseDto {
        logger.debug("Processing queue entry: $queueEntryId")
        val entry = dbQuery { queueRepository.findById(queueEntryId) } ?: throw queueEntryNotFound(queueEntryId)
        if (!canAttempt(entry)) {
            logger.warn(
                "Queue entry $queueEntryId cannot be retried " +
                    "(status=${entry.signingStatus}, retries=${entry.retryCount}/${entry.maxRetries})",
            )
            return dbQuery { (queueRepository.findById(queueEntryId) ?: throw queueEntryNotFound(queueEntryId)).toQueueEntryResponseDto() }
        }
        dbQuery {
            queueRepository.markAsProcessing(queueEntryId) ?: throw queueEntryNotFound(queueEntryId)
            updateEntitySigningStatus(entry.referenceId, entry.entityType, TseSigningStatus.PROCESSING)
        }

        // E2E R2-7: the device is called with NO database transaction open. The signer commits the
        // TANR before the device signs, and the signature right after, in their own transactions.
        val tseTransaction =
            try {
                signer.sign(TseSignRequest(entry.referenceId, entry.entityType, entry.processType, entry.processData, entry.clientId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return dbQuery { recordFailure(queueEntryId, entry, e, traceId) }
            }

        return try {
            dbQuery { recordSuccess(queueEntryId, entry, tseTransaction, traceId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The signature is committed; the next attempt adopts it without calling the device.
            dbQuery { recordFailure(queueEntryId, entry, e, traceId) }
        }
    }

    /**
     * Whether this attempt may proceed. `canRetry()` answers for the ordinary states; PROCESSING is
     * additionally claimable once its LEASE has expired.
     *
     * PROCESSING is committed BEFORE the device call, so a kill during signing leaves the entry in
     * a status that `TseSigningStatus.canRetry()` refuses forever — a fiscal Vorgang unsigned,
     * never retried, never FAILED, with no alarm. The lease is applied here rather than by widening
     * the shared enum, because PROCESSING genuinely is not retryable while a worker holds it; only
     * an EXPIRED claim is. Resuming is safe: the signer adopts the STARTED row and finishes the
     * same transaction number instead of signing the payload again.
     */
    private fun canAttempt(entry: TseSigningQueue): Boolean {
        if (entry.canRetry()) return true
        if (entry.signingStatus != TseSigningStatus.PROCESSING) return false
        if (entry.isMaxRetriesExceeded()) return false
        val lastAttempt = entry.lastAttemptAt ?: return true
        val expiry =
            Clock.System
                .now()
                .minus(TseSigningQueueRepository.PROCESSING_LEASE_MINUTES, DateTimeUnit.MINUTE)
                .toLocalDateTime(TimeZone.currentSystemDefault())
        return lastAttempt < expiry
    }

    /** Audit, markAsSigned, link, SIGNED, late-signing record. Requires an ambient transaction. */
    private suspend fun recordSuccess(
        queueEntryId: Long,
        entry: TseSigningQueue,
        tseTransaction: TseTransaction,
        traceId: String?,
    ): TseSigningQueueDto.QueueEntryResponseDto {
        // Audit log for successful queued TSE signing (Nachsignieren)
        try {
            transactionAuditService.logTransaction(
                employeeId = "SYSTEM",
                employeeNumber = "SYSTEM",
                authorizationType = "AUTOMATIC",
                authorizingManagerId = "SYSTEM",
                operation = "TSE_QUEUE_SIGNED",
                entityType = entry.entityType.name,
                entityId = entry.referenceId,
                entityName = "Nachsignieren ${entry.entityType} ${entry.referenceId}",
                deviceId = entry.clientId,
                fiscalRecordId = tseTransaction.id.value.toString(),
                metadata =
                    mapOf(
                        "queueEntryId" to entry.id.value.toString(),
                        "retryCount" to entry.retryCount.toString(),
                        "processType" to entry.processType,
                    ),
                traceId = traceId,
            )
        } catch (e: Exception) {
            logger.error(
                "Audit log FAILED for TSE_QUEUE_SIGNED ${entry.entityType} ${entry.referenceId} — Nachsignieren not recorded",
                e,
            )
        }

        val signed =
            queueRepository.markAsSigned(queueEntryId, tseTransaction)
                ?: throw queueEntryNotFound(queueEntryId)

        updateEntityWithTseTransaction(entry.referenceId, entry.entityType, tseTransaction)
        updateEntitySigningStatus(entry.referenceId, entry.entityType, TseSigningStatus.SIGNED)
        // Keeps the Vorgang distinguishable from one signed on time once its status is SIGNED.
        lateSigningRepository.markSigned(entry.entityType, entry.referenceId, currentTimeStamp(), tseTransaction.id.value)

        logger.info("Queue entry $queueEntryId signed successfully, tseTransactionId=${tseTransaction.id.value}")
        return signed.toQueueEntryResponseDto()
    }

    /** M7 timeout audit, then retry or FAILED bookkeeping. Requires an ambient transaction. */
    private suspend fun recordFailure(
        queueEntryId: Long,
        entry: TseSigningQueue,
        e: Exception,
        traceId: String?,
    ): TseSigningQueueDto.QueueEntryResponseDto {
        // B1: this caught only PosException, so a NON-PosException raised while PERSISTING the
        // signature (the ExposedSQLException the over-long process_data_hash produced) escaped
        // the OUTER dbQuery and rolled back markAsProcessing AND the retry bookkeeping with
        // it. retryCount never incremented, the entry stayed PENDING, and the worker re-signed
        // the SAME payload on the TSE every RETRY_INTERVAL_SECONDS forever — burning the
        // device's finite signature budget with no alarm, because the entry could never reach
        // FAILED. Every failure must now be counted, whatever its type.
        val errorMessage = e.message ?: (e as? PosException)?.code?.name ?: e::class.simpleName ?: "unknown error"
        val errorCode = (e as? PosException)?.code?.name ?: e::class.simpleName ?: "UNKNOWN_ERROR"

        // M7: TSE_COMMUNICATION_TIMEOUT means the request REACHED the device and no response
        // came back — the TSE may already hold a signature for this payload, and nothing was
        // persisted locally, so the outcome is unknowable from here. We still retry (a Vorgang
        // must not stay unsigned), but the resulting duplicate has to be EXPLAINABLE to an
        // auditor rather than silently present in the WORM. TSE_CONNECTION_FAILED is different:
        // the device never processed it, so a retry carries no duplicate risk.
        if ((e as? PosException)?.code == PosErrorCode.TSE_COMMUNICATION_TIMEOUT) {
            try {
                transactionAuditService.logTransaction(
                    employeeId = "SYSTEM",
                    employeeNumber = "SYSTEM",
                    authorizationType = "AUTOMATIC",
                    authorizingManagerId = "SYSTEM",
                    operation = TSE_POSSIBLE_DOUBLE_SIGN,
                    entityType = entry.entityType.name,
                    entityId = entry.referenceId,
                    entityName = "Nachsignieren ${entry.entityType} ${entry.referenceId}",
                    deviceId = entry.clientId,
                    reason =
                        "TSE response lost after the request was sent; a retry may produce " +
                            "a second signature for the same Vorgang",
                    metadata =
                        mapOf(
                            "queueEntryId" to entry.id.value.toString(),
                            "processType" to entry.processType,
                            "retryCount" to entry.retryCount.toString(),
                        ),
                    traceId = traceId,
                )
            } catch (audit: Exception) {
                logger.error(
                    "Audit log FAILED for $TSE_POSSIBLE_DOUBLE_SIGN ${entry.entityType} " +
                        "${entry.referenceId} — the duplicate risk is now UNRECORDED",
                    audit,
                )
            }
        }

        return if (entry.retryCount + 1 >= entry.maxRetries) {
            logger.error("Queue entry $queueEntryId failed after max retries: $errorMessage")
            val failed =
                queueRepository.markAsFailed(queueEntryId, errorMessage, errorCode)
                    ?: throw queueEntryNotFound(queueEntryId)
            updateEntitySigningStatus(entry.referenceId, entry.entityType, TseSigningStatus.FAILED)
            failed.toQueueEntryResponseDto()
        } else {
            val nextRetryDelay = entry.calculateNextRetryDelay()
            val nextRetryAt =
                Clock.System
                    .now()
                    .plus(nextRetryDelay, DateTimeUnit.SECOND)
                    .toLocalDateTime(TimeZone.currentSystemDefault())

            logger.warn("Queue entry $queueEntryId signing failed, queued for retry in ${nextRetryDelay}s: $errorMessage")
            val retried =
                queueRepository.markForRetry(queueEntryId, errorMessage, errorCode, nextRetryAt)
                    ?: throw queueEntryNotFound(queueEntryId)
            updateEntitySigningStatus(entry.referenceId, entry.entityType, TseSigningStatus.QUEUED)
            retried.toQueueEntryResponseDto()
        }
    }

    override suspend fun processBatch(
        batchSize: Int,
        ignoreBackoff: Boolean,
    ): ServerQueueDto.BatchProcessingResultDto {
        logger.info("Processing batch of up to $batchSize queue entries")

        val startTime = System.currentTimeMillis()
        var processedCount = 0
        var successCount = 0
        var failureCount = 0
        var queuedForRetryCount = 0
        var skippedCount = 0
        val errors = mutableListOf<ServerQueueDto.ProcessingErrorDto>()

        val tseAvailable = isTseAvailable()
        if (!tseAvailable) {
            logger.warn("TSE not available, skipping batch processing")
            return ServerQueueDto.BatchProcessingResultDto(
                processedCount = 0,
                successCount = 0,
                failureCount = 0,
                queuedForRetryCount = 0,
                skippedCount = 0,
                processingTimeMs = System.currentTimeMillis() - startTime,
                errors = emptyList(),
            )
        }

        val entries =
            dbQuery {
                try {
                    queueRepository.findReadyForProcessing(batchSize, ignoreBackoff)
                } catch (e: Exception) {
                    logger.error("Failed to get queue entries: ${e.message}", e)
                    emptyList()
                }
            }

        logger.info("Found ${entries.size} entries ready for processing")

        for (entry in entries) {
            processedCount++
            try {
                // Explicit null. The sweeper is genuinely background work, and MDC is thread-local
                // on a pooled dispatcher — reading it here could pick up a trace id left behind by
                // whichever request last used this thread, and a WRONG trace id on a fiscal row is
                // worse than none.
                val updatedEntry = processEntry(entry.id.value, traceId = null)
                when (updatedEntry.signingStatus) {
                    TseSigningStatus.SIGNED -> successCount++
                    TseSigningStatus.QUEUED -> queuedForRetryCount++
                    TseSigningStatus.FAILED -> {
                        failureCount++
                        errors.add(
                            ServerQueueDto.ProcessingErrorDto(
                                queueEntryId = updatedEntry.id,
                                referenceId = updatedEntry.referenceId,
                                entityType = updatedEntry.entityType,
                                errorCode = updatedEntry.lastErrorCode,
                                errorMessage = updatedEntry.lastError ?: "Unknown error",
                            ),
                        )
                    }
                    else -> skippedCount++
                }
                if (updatedEntry.signingStatus != TseSigningStatus.SIGNED && updatedEntry.lastErrorCode in BATCH_STOP_CODES) {
                    val remaining = entries.size - processedCount
                    skippedCount += remaining
                    logger.warn("TSE unavailable mid-batch (${updatedEntry.lastErrorCode}); $remaining entries keep their turn")
                    break
                }
            } catch (e: Exception) {
                failureCount++
                errors.add(
                    ServerQueueDto.ProcessingErrorDto(
                        queueEntryId = entry.id.value,
                        referenceId = entry.referenceId,
                        entityType = entry.entityType,
                        errorCode = "PROCESSING_ERROR",
                        errorMessage = e.message ?: e::class.simpleName.orEmpty(),
                    ),
                )
            }
        }

        val processingTimeMs = System.currentTimeMillis() - startTime
        logger.info(
            "Batch processing complete: processed=$processedCount, success=$successCount, " +
                "failed=$failureCount, queued=$queuedForRetryCount, skipped=$skippedCount, time=${processingTimeMs}ms",
        )

        return ServerQueueDto.BatchProcessingResultDto(
            processedCount = processedCount,
            successCount = successCount,
            failureCount = failureCount,
            queuedForRetryCount = queuedForRetryCount,
            skippedCount = skippedCount,
            processingTimeMs = processingTimeMs,
            errors = errors,
        )
    }

    override suspend fun getQueueStatus(): TseSigningQueueDto.QueueStatusDto =
        dbQuery {
            logger.debug("Getting queue status")

            val statusCounts = queueRepository.getStatusCounts()

            val totalEntries = statusCounts.values.sum()
            val pendingCount = statusCounts[TseSigningStatus.PENDING] ?: 0
            val processingCount = statusCounts[TseSigningStatus.PROCESSING] ?: 0
            val queuedCount = statusCounts[TseSigningStatus.QUEUED] ?: 0
            val signedCount = statusCounts[TseSigningStatus.SIGNED] ?: 0
            val failedCount = statusCounts[TseSigningStatus.FAILED] ?: 0
            val cancelledCount = statusCounts[TseSigningStatus.CANCELLED] ?: 0

            val tseAvailable = tseSignClient.checkHealth()?.healthy == true

            // The OLDEST waiting entry, PENDING or QUEUED. This read PENDING only, newest first, so it
            // named the most recent entry and nothing once every entry had been tried (E2E round 4,
            // R4-8). The value is the entry's creation time; the client derives the age from it.
            val oldestPendingAge =
                queueRepository
                    .findOldestAwaitingSigning()
                    ?.createdAt
                    ?.toString()

            val averageRetryCount =
                if (totalEntries > 0) {
                    val failedEntries = queueRepository.findByStatus(TseSigningStatus.FAILED, limit = 100)
                    if (failedEntries.isNotEmpty()) {
                        failedEntries.map { it.retryCount }.average()
                    } else {
                        0.0
                    }
                } else {
                    0.0
                }

            TseSigningQueueDto.QueueStatusDto(
                totalEntries = totalEntries,
                pendingCount = pendingCount,
                processingCount = processingCount,
                queuedCount = queuedCount,
                signedCount = signedCount,
                failedCount = failedCount,
                cancelledCount = cancelledCount,
                tseAvailable = tseAvailable,
                retryServiceRunning = TseSigningRetryService.isRunning(),
                oldestPendingAge = oldestPendingAge,
                averageRetryCount = averageRetryCount,
            )
        }

    override suspend fun getEntry(queueEntryId: Long): TseSigningQueueDto.QueueEntryResponseDto =
        dbQuery {
            val entry =
                queueRepository.findById(queueEntryId)
                    ?: throw queueEntryNotFound(queueEntryId)
            entry.toQueueEntryResponseDto()
        }

    override suspend fun listEntries(
        status: TseSigningStatus?,
        entityType: TseEntityType?,
        limit: Int,
        offset: Int,
    ): List<TseSigningQueueDto.QueueEntryResponseDto> =
        dbQuery {
            val entries =
                when {
                    status != null -> queueRepository.findByStatus(status, limit, offset)
                    entityType != null -> queueRepository.findByEntityType(entityType, limit, offset)
                    else -> queueRepository.findByStatus(TseSigningStatus.PENDING, limit, offset)
                }
            entries.map { it.toQueueEntryResponseDto() }
        }

    override suspend fun retryEntry(queueEntryId: Long): TseSigningQueueDto.QueueEntryResponseDto {
        // A manual retry is request-backed: its only caller is TseSigningQueueRoutes, driven by an
        // operator. Captured HERE, before the dbQuery below, because MDC is thread-local and the
        // audit write happens inside processEntry — past two suspension points from this line.
        val traceId = MDC.get(TRACE_ID_MDC_KEY)
        logger.info("Manual retry requested for queue entry: $queueEntryId")

        dbQuery {
            queueRepository.findById(queueEntryId)
                ?: throw queueEntryNotFound(queueEntryId)
            queueRepository.updateStatus(queueEntryId, TseSigningStatus.PENDING)
                ?: throw queueEntryNotFound(queueEntryId)
        }

        // Trigger immediate processing (own dbQuery).
        return processEntry(queueEntryId, traceId)
    }

    override suspend fun cancelEntry(
        queueEntryId: Long,
        employeeId: String,
        reason: String,
    ): TseSigningQueueDto.QueueEntryResponseDto {
        // Captured BEFORE the dbQuery. Abandoning a signature is request-backed — its only caller is
        // TseSigningQueueRoutes, taking the employee and the mandatory reason from an operator's
        // request — and the row it writes is the one an auditor most needs to tie back to that
        // request. MDC is thread-local, so reading it inside the transaction below can find nothing.
        val traceId = MDC.get(TRACE_ID_MDC_KEY)
        return dbQuery {
            logger.info("Cancelling queue entry: $queueEntryId (by=$employeeId)")

            // M8: the outcome is a permanently unsigned Beleg, so it may not be unexplained.
            if (reason.isBlank()) {
                throw PosException(
                    code = PosErrorCode.VALIDATION_FAILED,
                    httpStatus = HttpStatusCode.BadRequest,
                    message =
                        "A reason is required to abandon TSE signing — the Vorgang stays " +
                            "permanently unsigned (§146a AO).",
                )
            }

            val entry =
                queueRepository.findById(queueEntryId)
                    ?: throw queueEntryNotFound(queueEntryId)

            val cancelled =
                queueRepository.cancel(queueEntryId)
                    ?: throw queueEntryNotFound(queueEntryId)

            updateEntitySigningStatus(entry.referenceId, entry.entityType, TseSigningStatus.CANCELLED)
            lateSigningRepository.markAbandoned(entry.entityType, entry.referenceId, currentTimeStamp())

            // Mirrors the TSE_QUEUE_SIGNED entry the success path writes. Abandoning a signature is
            // the more consequential of the two and previously left no trace whatsoever.
            //
            // NOT caught. The abandonment and its record are one act: a cancel whose audit row could
            // not be written must fail, so the entry stays queued and the operator sees the error.
            // Catching it here was worse than an unrecorded abandonment on Postgres, where the failed
            // INSERT aborts this transaction and pgjdbc turns the COMMIT into a silent ROLLBACK — the
            // operator was told the cancel worked while nothing, not even the cancel, was written.
            // A savepoint would keep the transaction usable, but would commit exactly the unrecorded
            // abandonment M8 exists to prevent.
            transactionAuditService.logTransaction(
                employeeId = employeeId,
                employeeNumber = employeeId,
                authorizationType = "MANUAL",
                authorizingManagerId = employeeId,
                operation = TSE_QUEUE_CANCELLED,
                entityType = entry.entityType.name,
                entityId = entry.referenceId,
                entityName = "Nachsignieren abgebrochen ${entry.entityType} ${entry.referenceId}",
                deviceId = entry.clientId,
                reason = reason,
                metadata =
                    mapOf(
                        "queueEntryId" to entry.id.value.toString(),
                        "processType" to entry.processType,
                        "retryCount" to entry.retryCount.toString(),
                    ),
                traceId = traceId,
            )

            cancelled.toQueueEntryResponseDto()
        }
    }

    override suspend fun isTseAvailable(): Boolean =
        try {
            tseSignClient.checkHealth()?.healthy == true
        } catch (e: Exception) {
            logger.warn("TSE availability check failed", e)
            false
        }

    override suspend fun cleanupOldEntries(olderThanDays: Int): Int =
        dbQuery {
            logger.info("Cleaning up queue entries older than $olderThanDays days")

            val cutoffDate =
                Clock.System
                    .now()
                    .minus(olderThanDays.toLong(), DateTimeUnit.DAY, TimeZone.currentSystemDefault())
                    .toLocalDateTime(TimeZone.currentSystemDefault())

            val deleted = queueRepository.deleteOldEntries(cutoffDate)
            logger.info("Deleted $deleted old queue entries")
            deleted
        }

    // ================================================================================
    // Private Helper Methods
    // ================================================================================

    private fun queueEntryNotFound(id: Long): PosException =
        PosException(
            code = PosErrorCode.RESOURCE_NOT_FOUND,
            httpStatus = HttpStatusCode.NotFound,
            message = "TSE signing queue entry $id not found",
        )

    /** Updates the signing status on the original entity. */
    private fun updateEntitySigningStatus(
        referenceId: String,
        entityType: TseEntityType,
        status: TseSigningStatus,
    ) {
        try {
            when (entityType) {
                TseEntityType.BILL -> {
                    val billId = referenceId.toLong()
                    BillTable.update({ BillTable.id eq billId }) {
                        it[tseSigningStatus] = status
                    }
                }
                TseEntityType.CANCELLATION -> {
                    cancellationsInBatch(referenceId).forEach { it.tseSigningStatus = status }
                }
                TseEntityType.ORDER -> {
                    val orderId = referenceId.toLong()
                    OrdersTable.update({ OrdersTable.id eq orderId }) {
                        it[tseSigningStatus] = status
                    }
                }
                TseEntityType.KASSENBUCH -> {
                    val kassenbuchId = referenceId.toLong()
                    KassenbuchHeaderTable.update({ KassenbuchHeaderTable.id eq kassenbuchId }) {
                        it[tseSigningStatus] = status
                    }
                }
                TseEntityType.VOUCHER -> {
                    logger.debug("Skipping signing status update for VOUCHER $referenceId")
                }
            }
        } catch (e: NumberFormatException) {
            // A malformed referenceId means the signature can NEVER be linked to its entity —
            // swallowing left the Vorgang permanently „ausstehend" while the queue reported
            // success (audit H9). Fail the entry loudly instead.
            throw IllegalStateException(
                "referenceId '$referenceId' for $entityType is not a numeric entity id — signature cannot be linked",
                e,
            )
        } catch (e: Exception) {
            logger.error("Failed to update signing status for $entityType $referenceId", e)
        }
    }

    /**
     * The `Cancellation` rows one cancel action created, by the batch id its producer queued.
     *
     * `OrderItemServiceImpl.cancelOrderItems` enqueues `referenceId = batchId` (a UUID) because the
     * former `items.joinToString(";")` overflowed [TseSigningQueueTable.referenceId] (varchar(100))
     * at ~12 items. This consumer used to parse that reference back into OrderItem ids, so a UUID
     * threw `NumberFormatException` inside [enqueue]'s own transaction and rolled the queue row
     * back — the Storno committed unsigned with neither a queue row nor a `tse_late_signing` row,
     * and nothing re-drives it (`TsePendingSweeper` is Bill-only). Resolving by batch id makes the
     * two sides agree.
     */
    private fun cancellationsInBatch(referenceId: String): List<Cancellation> {
        val byBatch = Cancellation.find { CancellationTable.batchId eq referenceId }.toList()
        if (byBatch.isNotEmpty()) return byBatch

        // Rows queued before the producer switched to the batch id still carry
        // `items.joinToString(";")`. Resolving them by batch id alone finds nothing and this arm
        // would then succeed having linked nothing — the silent no-op H9 exists to prevent. The
        // legacy shape stays readable; a reference that is neither still throws below.
        val legacyItemIds = referenceId.split(";").map { it.trim().toLong() }
        return legacyItemIds.mapNotNull { itemId ->
            Cancellation.find { CancellationTable.orderItemId eq itemId }.firstOrNull()
        }
    }

    /** Updates the original entity with the TSE transaction reference. */
    private fun updateEntityWithTseTransaction(
        referenceId: String,
        entityType: TseEntityType,
        tseTransaction: TseTransaction,
    ) {
        try {
            when (entityType) {
                TseEntityType.BILL -> {
                    val billId = referenceId.toLong()
                    val bill = Bill.findById(billId)
                    bill?.tseTransaction = tseTransaction
                }
                TseEntityType.CANCELLATION -> {
                    cancellationsInBatch(referenceId).forEach { it.tseTransaction = tseTransaction }
                }
                TseEntityType.ORDER -> {
                    val orderId = referenceId.toLong()
                    val order = Orders.findById(orderId)
                    order?.tseTransaction = tseTransaction
                }
                TseEntityType.KASSENBUCH -> {
                    val kassenbuchId = referenceId.toLong()
                    val kassenbuchHeader = KassenbuchHeader.findById(kassenbuchId)
                    kassenbuchHeader?.tseTransaction = tseTransaction
                }
                TseEntityType.VOUCHER -> {
                    logger.debug("Skipping TSE transaction update for VOUCHER $referenceId")
                }
            }
            logger.debug("Updated $entityType $referenceId with tseTransactionId=${tseTransaction.id.value}")
        } catch (e: NumberFormatException) {
            // See updateSigningStatus: an unlinkable signature must fail loudly (audit H9).
            throw IllegalStateException(
                "referenceId '$referenceId' for $entityType is not a numeric entity id — signature cannot be linked",
                e,
            )
        } catch (e: Exception) {
            logger.error("Failed to update $entityType $referenceId with TSE transaction", e)
        }
    }
}
