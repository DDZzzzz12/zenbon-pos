/**
 * Service interface for the Kassensturz (physical count reconciliation) of a non-fiscal cash book.
 *
 * A cash book is either the shop's fiscal Ladenkasse (closed by the Z-Bon — the legally defined
 * Kassenabschluss) or a non-fiscal Tresor/Nebenkasse (ordinary bookkeeping, closed by an operator
 * physically counting the safe and comparing it against the book). See
 * `docs/superpowers/plans/2026-08-07-cash-book-dimension.md`, Task 20.
 */
package org.example.project.features.kassenbuch.domain.service

import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import java.math.BigDecimal

/**
 * Previews and records the Kassensturz for a shop's non-fiscal cash books.
 */
interface CashBookCloseService {
    /**
     * Builds a read-only preview of the next period this book is ready to close.
     *
     * Creates nothing — no `cash_book_close` row is written, nothing is locked. It exists so the
     * UI can show the operator which period they are about to close and what the book says
     * should be counted, before they enter the physically counted amount ([close]).
     *
     * `periodStart` is always derived, never supplied: the day after the previous close's
     * `periodEnd` when this book has been closed before, otherwise the date of the book's
     * earliest entry. This is what keeps periods gapless.
     *
     * `periodEnd` defaults from the book's `closeInterval` (end of the ISO week / month / quarter
     * containing `periodStart`; `today` for `MANUAL`), capped at `today` — a period that has not
     * happened yet cannot be closed.
     *
     * @throws org.example.project.infrastructure.errors.PosException if the book does not exist,
     *   is the shop's fiscal book (the Ladenkasse is closed by the Z-Bon, not this flow), or has
     *   nothing to close (no entries at all and no previous close).
     */
    suspend fun preview(cashBookId: Long): CashBookDto.ClosePreviewDto

    /**
     * Records the Kassensturz: compares the operator's physical count against the book, books the
     * difference as a real accounting entry, and locks the period.
     *
     * Reuses [preview]'s guards and period derivation, so a book that cannot be previewed cannot
     * be closed either. `difference = countedBalance − bookBalance`; when it is non-zero, ONE
     * `CASH_SURPLUS` (positive) or `CASH_SHORTAGE` (negative) entry is booked in [cashBookId]'s own
     * belegNr series with a mandatory Eigenbeleg, dated `periodEnd`. A Kassensturz that never
     * books the delta is bookkeeping theatre — the book would stay knowingly wrong and the
     * discrepancy would compound into the next period.
     *
     * Every entry up to and including `periodEnd` — the difference entry included, even though it
     * is created just now and so its `createdAt` may fall after `periodEnd` — is locked
     * (`isLocked = true`). Entries dated after `periodEnd` stay unlocked. The difference entry is
     * never TSE-signed: this path only ever runs against a non-fiscal book.
     *
     * Everything happens in one transaction, serialized against the Z-Bon closing via the same
     * `pg_advisory_xact_lock(1)` used by [CashBookTransferService.createTransfer] and
     * `KassenbuchServiceImpl.createSignedEntry` — this path creates a Kassenbuch entry too.
     *
     * @throws org.example.project.infrastructure.errors.PosException under the same conditions as
     *   [preview], or if [employeeId] does not resolve to an employee.
     */
    suspend fun close(
        employeeId: String,
        cashBookId: Long,
        countedBalance: BigDecimal,
        notes: String? = null,
    ): CashBookDto.CloseResponseDto

    /**
     * The close history for [cashBookId], most recent [CashBookDto.CloseResponseDto.periodEnd]
     * first. Read-only — creates and locks nothing, unlike [close].
     *
     * @throws org.example.project.infrastructure.errors.PosException if the book does not exist.
     */
    suspend fun closeHistory(cashBookId: Long): List<CashBookDto.CloseResponseDto>
}
