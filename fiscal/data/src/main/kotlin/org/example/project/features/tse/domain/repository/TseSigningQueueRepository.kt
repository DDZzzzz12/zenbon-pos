/**
 * Repository interface for TSE signing queue data access operations.
 *
 * Per `pos-server/CLAUDE.md`: repositories never return Result and never throw
 * `PosException` — just plain data access. Lookups return `T?`; create/update
 * return the entity.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.tse.domain.models.TseSigningQueue
import org.example.project.features.tse.domain.models.TseTransaction

interface TseSigningQueueRepository {
    companion object {
        /**
         * How long a PROCESSING entry is considered leased by the worker that claimed it.
         *
         * PROCESSING is committed BEFORE the device is called, so a kill during signing would
         * otherwise strand a fiscal Vorgang forever: `TseSigningStatus.PROCESSING.canRetry()` is
         * false and nothing ever moves it to FAILED — unsigned, never retried, no alarm. Treating
         * the status as a LEASE instead makes the entry claimable again once it expires, and the
         * signer's STARTED-row adoption makes the resumed attempt safe: it FINISHES the same
         * transaction number rather than signing the payload a second time.
         *
         * The value must exceed the longest plausible device call (including the driver's own
         * timeouts and a restart of the connection) so a worker that is merely slow is never raced
         * by a second one; the Swissbit driver's operations are bounded in seconds, so fifteen
         * minutes is generous by two orders of magnitude while still bounding how long an
         * interrupted Vorgang stays invisible.
         *
         * The claim is NOT atomic: the select and the `markAsProcessing` are separate statements
         * with no `SELECT … FOR UPDATE`, so two workers can both claim the same entry — the lease
         * narrows that window, it does not close it. What actually prevents a double signature is
         * the partial unique index `tse_transaction_one_live_per_vorgang` (V82), which fails the
         * loser's first commit. That index is PostgreSQL-only, so this safety rests on running
         * PostgreSQL, which production does; a deployment whose index had to be created non-unique
         * (see the migration) is protected only by the application-level checks until its
         * duplicates are resolved.
         */
        const val PROCESSING_LEASE_MINUTES = 15L
    }

    /** Creates a new entry in the signing queue. */
    suspend fun create(
        referenceId: String,
        entityType: TseEntityType,
        processType: String,
        processData: String,
        clientId: String,
        priority: Int = 5,
        openTseTransactionNumber: Long? = null,
    ): TseSigningQueue

    /** Retrieves a queue entry by its ID, or null if not found. */
    suspend fun findById(id: Long): TseSigningQueue?

    /** Retrieves a queue entry by entity reference, or null if not found. */
    suspend fun findByReference(
        referenceId: String,
        entityType: TseEntityType,
    ): TseSigningQueue?

    /**
     * True if ANY queue row already references this entity, regardless of status (incl. terminal
     * SIGNED/FAILED/CANCELLED). Existence of any row means the worker path already owns this
     * Vorgang, so the PENDING-sweeper (TSER-9) must not double-enqueue it.
     */
    suspend fun existsForReference(
        referenceId: String,
        entityType: TseEntityType,
    ): Boolean

    /**
     * Retrieves queue entries ready for processing: PENDING/QUEUED whose retry time has come, plus
     * PROCESSING entries whose lease has expired (see [PROCESSING_LEASE_MINUTES]).
     *
     * [ignoreBackoff] also takes PENDING/QUEUED entries whose retry time has not come yet — what an
     * operator's "retry now" means once they have removed the cause (E2E round 4, R4-8). An
     * unexpired PROCESSING lease is never taken either way: that entry is on the device right now.
     */
    suspend fun findReadyForProcessing(
        limit: Int = 50,
        ignoreBackoff: Boolean = false,
    ): List<TseSigningQueue>

    /** The oldest entry still waiting to be signed (PENDING or QUEUED), or null when none waits. */
    suspend fun findOldestAwaitingSigning(): TseSigningQueue?

    /** Retrieves queue entries by signing status. */
    suspend fun findByStatus(
        status: TseSigningStatus,
        limit: Int = 100,
        offset: Int = 0,
    ): List<TseSigningQueue>

    /** Retrieves queue entries by entity type. */
    suspend fun findByEntityType(
        entityType: TseEntityType,
        limit: Int = 100,
        offset: Int = 0,
    ): List<TseSigningQueue>

    /** Updates the status of a queue entry. Returns null if not found. */
    suspend fun updateStatus(
        id: Long,
        status: TseSigningStatus,
        error: String? = null,
        errorCode: String? = null,
    ): TseSigningQueue?

    /** Marks a queue entry as processing (in progress). Returns null if not found. */
    suspend fun markAsProcessing(id: Long): TseSigningQueue?

    /** Marks a queue entry as successfully signed. Returns null if not found. */
    suspend fun markAsSigned(
        id: Long,
        tseTransaction: TseTransaction,
    ): TseSigningQueue?

    /** Marks a queue entry for retry (queued status). Returns null if not found. */
    suspend fun markForRetry(
        id: Long,
        error: String,
        errorCode: String? = null,
        nextRetryAt: LocalDateTime,
    ): TseSigningQueue?

    /** Marks a queue entry as permanently failed. Returns null if not found. */
    suspend fun markAsFailed(
        id: Long,
        error: String,
        errorCode: String? = null,
    ): TseSigningQueue?

    /** Cancels a queue entry. Returns null if not found. */
    suspend fun cancel(id: Long): TseSigningQueue?

    /** Counts queue entries by status. */
    suspend fun countByStatus(status: TseSigningStatus? = null): Long

    /** Gets queue statistics summary. */
    suspend fun getStatusCounts(): Map<TseSigningStatus, Long>

    /** Deletes old completed/failed/cancelled entries. */
    suspend fun deleteOldEntries(
        olderThan: LocalDateTime,
        statuses: List<TseSigningStatus> =
            listOf(
                TseSigningStatus.SIGNED,
                TseSigningStatus.FAILED,
                TseSigningStatus.CANCELLED,
            ),
    ): Int
}
