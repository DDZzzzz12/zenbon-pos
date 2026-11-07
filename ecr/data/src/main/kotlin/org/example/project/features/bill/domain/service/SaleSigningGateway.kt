package org.example.project.features.bill.domain.service

import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseFailureBehavior

/**
 * The order, cancellation and bill services' only way to TSE-sign a sale (spec §5, the hard seam).
 * The fiscal area implements it; the ecr services keep their own entity writes, the FAIL-mode
 * handling and their log lines. The accounting area's till clearing (`SalesInvoiceServiceImpl.settleAtTill`)
 * signs its clearing bill through it too.
 */
interface SaleSigningGateway {
    /**
     * Reads the TSE mode, then the failure behaviour, signs [request] (a [org.example.project.infrastructure.errors.PosException]
     * from signing becomes a failed outcome; anything else propagates) and resolves what the caller
     * must do. Runs outside any transaction, after the caller's Phase-1 commit. The mode is read
     * first, then the failure behaviour, each once, immediately before signing; an implementation (or
     * a fake) keeps that order.
     */
    suspend fun attempt(request: SaleSigningRequest): SaleSigningAttempt

    /**
     * As [attempt], but with the TSE [mode] and [failureBehavior] the caller already read — for a
     * caller that hoists both reads out of its Phase-1 transaction. Reads no configuration itself.
     */
    suspend fun attempt(
        request: SaleSigningRequest,
        mode: TseMode,
        failureBehavior: TseFailureBehavior,
    ): SaleSigningAttempt

    /**
     * Hands [request] to the Nachsignierung queue with [attempt]'s cause. Call only when
     * [SaleSigningAttempt.enqueue]; every exception from the queue propagates to the caller.
     */
    suspend fun enqueue(
        request: SaleSigningRequest,
        attempt: SaleSigningAttempt,
    )

    /** The Vorgang's Nachsignierung record, or null when it was never queued. Runs in the caller's transaction. */
    fun signingNoteFor(
        entityType: TseEntityType,
        referenceId: String,
    ): TseSigningNoteDto?
}
