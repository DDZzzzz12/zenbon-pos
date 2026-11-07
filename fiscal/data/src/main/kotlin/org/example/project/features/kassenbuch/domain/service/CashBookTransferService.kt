/**
 * Service interface for atomic book-to-book cash transfers (Geldtransit).
 *
 * Moving cash from the Ladenkasse (fiscal register drawer) to the Tresor (safe) — or between any
 * two of a shop's cash books — is one physical event, recorded once in each book. See
 * `docs/superpowers/plans/2026-08-07-cash-book-dimension.md`, Task 16.
 */
package org.example.project.features.kassenbuch.domain.service

import org.example.project.core.domain.dto.kassenbuch.CashBookDto

/**
 * Creates atomic two-leg transfers between a shop's cash books.
 */
interface CashBookTransferService {
    /**
     * Move cash between two books as one atomic pair.
     *
     * Both legs (`CASH_TRANSFER_OUT` on the source book, `CASH_TRANSFER_IN` on the destination
     * book) are created in a single transaction — a half-booked transfer would leave the two
     * books permanently disagreeing about money that physically moved, with no repair path. Each
     * leg draws its `belegNr` from its own book's series and gets its own Eigenbeleg (the
     * `linked_document_id` column carries a `uniqueIndex`, so the legs cannot share one document).
     *
     * At most one of the two books is the shop's fiscal Ladenkasse; that leg alone is signed
     * afterwards (Phase 2, not retried — a signing failure strands it `PENDING` for
     * `TsePendingSweeper`, exactly as for an ordinary entry). A transfer between two non-fiscal
     * books signs neither leg.
     *
     * @throws org.example.project.infrastructure.errors.PosException if the books differ, either
     *   is inactive, belong to different shops, or the source book's balance does not cover the
     *   requested amount.
     */
    suspend fun createTransfer(
        employeeId: String,
        deviceId: String?,
        request: CashBookDto.CreateTransferRequestDto,
    ): CashBookDto.TransferResponseDto

    /**
     * Reverse a transfer as a whole — never one leg.
     *
     * A transfer is one physical cash movement recorded in two books; stornoing a single leg
     * would leave the two books permanently disagreeing about cash that actually moved, with no
     * repair path. [org.example.project.features.kassenbuch.data.service.KassenbuchServiceImpl.createCorrection]
     * refuses any header carrying a `transferId` and points the caller here instead.
     *
     * Produces a Storno pair — one GoBD-compliant reversal entry per leg, in one transaction, each
     * `correctionRole = REVERSAL` and `correctsHeaderId` pointing at the leg it reverses. The
     * reversal pair shares a FRESH `transferId` (not the original's), so it is itself a
     * well-formed transfer and cannot be confused with the original when loading by id.
     *
     * @throws org.example.project.infrastructure.errors.PosException if the transfer does not
     *   exist (either leg missing), it has already been reversed, or [reason] is blank.
     */
    suspend fun reverseTransfer(
        employeeId: String,
        deviceId: String?,
        transferId: String,
        reason: String,
    ): CashBookDto.TransferResponseDto
}
