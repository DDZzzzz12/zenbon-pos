/**
 * PROTECTED MODULE — Do not modify without explicit authorization.
 * TSE operations are fiscally regulated (KassenSichV) and changes require careful review.
 *
 * Service interface for TSE signing queue operations.
 *
 * Provides business logic for managing the queue-based TSE signing system
 * that enables "Nachsignieren" (late signing) for German KassenSichV compliance.
 *
 * All methods throw `org.example.project.infrastructure.errors.PosException` on failure
 * (B1 amendment); see the cross-walk table in
 * `docs/superpowers/plans/2026-05-04-poserror-tse-migration.md`.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.domain.service

import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.tse.domain.dto.TseSigningQueueDto as ServerQueueDto

/**
 * Service interface for TSE signing queue operations.
 */
interface TseSigningQueueService {
    /** Enqueues a transaction for TSE signing. */
    suspend fun enqueue(
        referenceId: String,
        entityType: TseEntityType,
        processType: String,
        processData: String,
        clientId: String,
        /** Why the Vorgang could not be signed immediately; recorded permanently in `tse_late_signing`. */
        reason: TseLateSigningReason,
        /** Operator-readable cause, from the failure at queue time. */
        reasonText: String,
        /**
         * TSE transaction ALREADY STARTED for this Vorgang.
         *
         * VESTIGIAL since the signer resumes by (reference, entity type): it finds the Vorgang's
         * STARTED `tse_transaction` row itself and finishes THAT transaction number, so nothing
         * reads this value any more. Column and parameter are kept so stored rows stay readable
         * and callers need not change; do not build new behaviour on it (TSER-12).
         */
        openTseTransactionNumber: Long? = null,
    ): TseSigningQueueDto.QueueEntryResponseDto

    /** Attempts to sign a specific queue entry. */
    suspend fun processEntry(queueEntryId: Long): TseSigningQueueDto.QueueEntryResponseDto

    /**
     * Processes a batch of ready queue entries. [ignoreBackoff] is the operator's "retry now": it
     * does not wait out the back-off (see [TseSigningQueueRepository.findReadyForProcessing]).
     */
    suspend fun processBatch(
        batchSize: Int = 50,
        ignoreBackoff: Boolean = false,
    ): ServerQueueDto.BatchProcessingResultDto

    /** Gets the current queue status. */
    suspend fun getQueueStatus(): TseSigningQueueDto.QueueStatusDto

    /** Gets a specific queue entry by ID. */
    suspend fun getEntry(queueEntryId: Long): TseSigningQueueDto.QueueEntryResponseDto

    /** Lists queue entries with optional filtering. */
    suspend fun listEntries(
        status: TseSigningStatus? = null,
        entityType: TseEntityType? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): List<TseSigningQueueDto.QueueEntryResponseDto>

    /** Manually retries a failed or queued entry. */
    suspend fun retryEntry(queueEntryId: Long): TseSigningQueueDto.QueueEntryResponseDto

    /**
     * Cancels a pending or queued entry — i.e. deliberately abandons signing this Vorgang.
     *
     * M8: [employeeId] and [reason] are REQUIRED because the result is a permanently unsigned
     * Beleg. Previously this left no `TransactionAuditLog` trace at all, so nobody could say who
     * abandoned what or why, while the success path right beside it logged `TSE_QUEUE_SIGNED`.
     *
     * @throws org.example.project.infrastructure.errors.PosException if [reason] is blank.
     */
    suspend fun cancelEntry(
        queueEntryId: Long,
        employeeId: String,
        reason: String,
    ): TseSigningQueueDto.QueueEntryResponseDto

    /** Checks if the TSE is currently available for signing. */
    suspend fun isTseAvailable(): Boolean

    /** Cleans up old completed/failed/cancelled entries. */
    suspend fun cleanupOldEntries(olderThanDays: Int = 30): Int
}
