package org.example.project.features.tse.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason

/**
 * The permanent Nachsignierung record per Vorgang (`tse_late_signing`). Every method needs an
 * ambient transaction. Rows are never deleted.
 */
interface TseLateSigningRepository {
    /**
     * Records that the Vorgang went to the signing queue. The FIRST queueing wins: a later re-queue
     * (retry, pending sweeper) must not overwrite the original cause.
     */
    fun recordQueued(
        entityType: TseEntityType,
        referenceId: String,
        queuedAt: LocalDateTime,
        reason: TseLateSigningReason,
        reasonText: String,
    )

    /** Stamps the late signature on the Vorgang's record; a no-op when it was never queued. */
    fun markSigned(
        entityType: TseEntityType,
        referenceId: String,
        signedAt: LocalDateTime,
        tseTransactionId: Long?,
    )

    /** Stamps an operator-cancelled Nachsignierung; a no-op when it was never queued. */
    fun markAbandoned(
        entityType: TseEntityType,
        referenceId: String,
        at: LocalDateTime,
    )

    /**
     * The records of many Vorgänge of one [entityType] at once, keyed by reference id — for exports,
     * which must not query per row. Vorgänge never queued are absent from the map.
     */
    fun notesFor(
        entityType: TseEntityType,
        referenceIds: Collection<String>,
    ): Map<String, TseSigningNoteDto>

    /** The Vorgang's Nachsignierung record, or null when it was never queued. */
    fun noteFor(
        entityType: TseEntityType,
        referenceId: String,
    ): TseSigningNoteDto?
}
