/**
 * Service interface for cash-book CRUD (as opposed to the entries booked into one).
 *
 * A shop has exactly one **fiscal** book — the Ladenkasse — provisioned by the migration or on
 * first use, never over HTTP. This service only ever creates/updates NON-FISCAL books; the
 * `isFiscal` flag itself is not reachable through it.
 *
 * Methods throw [org.example.project.infrastructure.errors.PosException] on errors; the global
 * StatusPages handler converts to application/problem+json.
 */
package org.example.project.features.kassenbuch.domain.service

import org.example.project.core.domain.dto.kassenbuch.CashBookDto

interface CashBookService {
    /**
     * Lists the shop's cash books, each with its own current balance. Deactivated books are left out
     * unless [includeInactive]: daily operation (the till, the Z-Bon, the Kasse switch) never offers
     * one, but the management dialog must list it to let an operator see and re-activate it.
     */
    suspend fun list(includeInactive: Boolean = false): List<CashBookDto.ResponseDto>

    /**
     * Creates a NON-FISCAL cash book. [CashBookDto.CreateRequestDto] has no `isFiscal` field —
     * `isFiscal = false` is hardcoded here, never taken from the request.
     */
    suspend fun create(request: CashBookDto.CreateRequestDto): CashBookDto.ResponseDto

    /**
     * Updates a cash book's name/account/kost/interval/active/sort. [CashBookDto.UpdateRequestDto]
     * has no `isFiscal` field, so this can never flip it.
     *
     * @throws org.example.project.infrastructure.errors.PosException when the request would
     *   deactivate the fiscal book, or deactivate any book whose current balance is non-zero.
     */
    suspend fun update(
        bookId: Long,
        request: CashBookDto.UpdateRequestDto,
    ): CashBookDto.ResponseDto

    /** Tills that may be designated as the fiscal book's signing till. */
    suspend fun signingTerminalCandidates(bookId: Long): List<CashBookDto.SigningTerminalCandidateDto>

    /**
     * Designates (or with null clears) the till that signs the fiscal book's closing movements.
     * Audited as `SET_CASH_BOOK_SIGNING_TERMINAL`.
     *
     * @throws org.example.project.infrastructure.errors.PosException CASH_BOOK_SIGNER_INELIGIBLE when
     *   the book is not the fiscal one or the terminal may not sign.
     */
    suspend fun setSigningTerminal(
        bookId: Long,
        terminalId: Long?,
        actorId: String,
        deviceId: String,
    ): CashBookDto.ResponseDto
}
