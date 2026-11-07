/**
 * API client interface for cash books (Ladenkasse/Tresor), the Geldtransit between them, and the
 * Kassensturz close of a non-fiscal book.
 *
 * Deliberately separate from [KassenbuchApiClient], which owns the *entries* surface: books,
 * transfers and closes are a distinct concern, and folding them into that already 12-method
 * interface would make neither easy to hold in context.
 *
 * Throws-style (Stage C): calls throw `PosApiException`/`NetworkApiException`; wrap them in
 * `runCatchingApi { }` at the call site.
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.kassenbuch.CashBookDto

interface CashBookApiClient {
    /** The shop's cash books, each with its own balance — the balances are never summed. */
    /** Active books only, unless [includeInactive] — the management dialog's list, which also re-activates. */
    suspend fun listBooks(includeInactive: Boolean = false): List<CashBookDto.ResponseDto>

    /** Creates a NON-fiscal book; the request carries no `isFiscal` field to get wrong. */
    suspend fun createBook(request: CashBookDto.CreateRequestDto): CashBookDto.ResponseDto

    suspend fun updateBook(
        bookId: Long,
        request: CashBookDto.UpdateRequestDto,
    ): CashBookDto.ResponseDto

    /** Books an atomic two-leg transfer between two of the shop's books. */
    suspend fun createTransfer(request: CashBookDto.CreateTransferRequestDto): CashBookDto.TransferResponseDto

    /** Reverses BOTH legs of a transfer as a Storno pair. Manager-only server-side. */
    suspend fun reverseTransfer(
        transferId: String,
        reason: String,
    ): CashBookDto.TransferResponseDto

    /** Read-only look at the next Kassensturz period — creates nothing. */
    suspend fun closePreview(bookId: Long): CashBookDto.ClosePreviewDto

    /** Records the Kassensturz: books the counted/book difference and locks the period. */
    suspend fun close(
        bookId: Long,
        request: CashBookDto.CloseRequestDto,
    ): CashBookDto.CloseResponseDto

    /** A book's close history, most recent period first. */
    suspend fun closeHistory(bookId: Long): List<CashBookDto.CloseResponseDto>

    /** Tills that may sign the fiscal book's closing movements. */
    suspend fun listSigningTerminalCandidates(bookId: Long): List<CashBookDto.SigningTerminalCandidateDto>

    /** Designates (or with null clears) the fiscal book's signing till. */
    suspend fun setSigningTerminal(
        bookId: Long,
        terminalId: Long?,
    ): CashBookDto.ResponseDto
}
