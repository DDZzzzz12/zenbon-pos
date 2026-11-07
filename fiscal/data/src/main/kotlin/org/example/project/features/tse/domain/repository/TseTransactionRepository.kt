package org.example.project.features.tse.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.domain.models.TseTransaction

/**
 * Repository interface for TSE Transaction data access operations.
 *
 * Per `pos-server/CLAUDE.md`: repositories never return Result and never throw
 * `PosException` — just plain data access. Lookups return `T?`; create/update
 * return the entity.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
interface TseTransactionRepository {
    /**
     * Creates a new TSE transaction in the database.
     *
     * @param tseStartTimestamp the TSE hardware's own StartTransaction log time (epoch
     * seconds), when available. Distinct from [startTime] (the server clock).
     */
    suspend fun create(
        tseSerialNumber: String?,
        transactionNumber: Long,
        processType: String?,
        processDataHash: String?,
        signature: String?,
        startTime: LocalDateTime?,
        clientId: String?,
        tseMode: TseMode? = TseMode.SIMULATION,
        tseStartTimestamp: Long? = null,
        /** M7: the Vorgang this signature belongs to, so the TSE log is explainable per Vorgang. */
        referenceId: String? = null,
        entityType: String? = null,
    ): TseTransaction

    /**
     * The Vorgang's current TSE transaction: newest first, never a SUPERSEDED one. Requires an
     * ambient transaction.
     */
    suspend fun findCurrentByReference(
        referenceId: String,
        entityType: String,
    ): TseTransaction?

    /**
     * A signed row written before entity_type existed (E1), matched by reference AND process type
     * AND the exact process data, so it is the same Vorgang by construction. The process type is
     * part of the match because one reference id can carry two Vorgänge of different kinds, and
     * identical payloads across kinds are conceivable.
     * Oldest id first, so the adopted row is stable across calls. Requires an ambient transaction.
     */
    suspend fun findLegacySigned(
        referenceId: String,
        processType: String,
        processData: String,
    ): TseTransaction?

    /** Marks [tseTransactionId] SUPERSEDED. Requires an ambient transaction. */
    suspend fun markSuperseded(tseTransactionId: Long): TseTransaction?

    /** Points a SUPERSEDED row at its replacement. Requires an ambient transaction. */
    suspend fun linkReplacement(
        tseTransactionId: Long,
        replacementId: Long,
    )

    /**
     * Updates an existing TSE transaction with completion data.
     *
     * @return the updated entity, or null if no transaction with the given id exists.
     */
    suspend fun update(
        tseTransactionId: Long,
        processDataHash: String?,
        signature: String?,
        endTime: LocalDateTime?,
        exportedFileReference: String? = null,
        tseTimestamp: Long? = null,
        signatureCounter: Long? = null,
        signatureAlgorithm: String? = null,
        publicKey: String? = null,
        certificate: String? = null,
    ): TseTransaction?

    /** Retrieves a TSE transaction by its ID, or null if not found. */
    suspend fun findById(tseTransactionId: Long): TseTransaction?

    /** Retrieves all TSE transactions within a date range. */
    suspend fun findByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        limit: Int = 100,
        offset: Int = 0,
    ): List<TseTransaction>

    /** Retrieves TSE transactions by process type. */
    suspend fun findByProcessType(
        processType: String,
        limit: Int = 100,
        offset: Int = 0,
    ): List<TseTransaction>

    /** Retrieves TSE transactions by client ID. */
    suspend fun findByClientId(
        clientId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): List<TseTransaction>

    /** Retrieves completed TSE transactions (with end time). */
    suspend fun findCompleted(
        limit: Int = 100,
        offset: Int = 0,
    ): List<TseTransaction>

    /** Retrieves pending TSE transactions (without end time). */
    suspend fun findPending(
        limit: Int = 100,
        offset: Int = 0,
    ): List<TseTransaction>

    /** Counts total number of TSE transactions. */
    suspend fun count(): Long

    /** Counts TSE transactions within a date range. */
    suspend fun countByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
    ): Long

    /** Retrieves the latest transaction number for sequence management. */
    suspend fun getLatestTransactionNumber(): Long?

    /**
     * Deletes a TSE transaction by ID.
     *
     * @return true if a row was deleted, false if no row matched.
     */
    suspend fun delete(tseTransactionId: Long): Boolean

    /** Retrieves TSE transactions for export purposes. */
    suspend fun findForExport(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        completedOnly: Boolean = true,
    ): List<TseTransaction>
}
