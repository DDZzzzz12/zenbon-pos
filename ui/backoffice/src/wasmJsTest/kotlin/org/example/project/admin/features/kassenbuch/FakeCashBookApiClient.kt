package org.example.project.admin.features.kassenbuch

import org.example.project.core.data.remote.CashBookApiClient
import org.example.project.core.domain.dto.kassenbuch.CashBookDto

/**
 * Records what the Kassenbuch ViewModels ask of the cash-book API.
 *
 * The counters and last-request fields exist so a test can assert that a *request was never made* —
 * which is the only way to tell a client-side guard apart from one that merely hides a button while
 * still calling the server.
 */
class FakeCashBookApiClient(
    /**
     * Mutable so a test can move the server's answer between two `listBooks()` calls — that is how
     * a guard which re-checks the *loaded* books, rather than the form it is submitting, is proved.
     */
    var books: List<CashBookDto.ResponseDto> = emptyList(),
    private val preview: (Long) -> CashBookDto.ClosePreviewDto = { bookId ->
        CashBookDto.ClosePreviewDto(
            cashBookId = bookId,
            cashBookName = "Tresor",
            periodStart = "2026-08-01",
            periodEnd = "2026-08-09",
            bookBalance = "128.50",
        )
    },
) : CashBookApiClient {
    var closePreviewCalls: Int = 0
        private set

    var lastCloseRequest: CashBookDto.CloseRequestDto? = null
        private set

    val createRequests: MutableList<CashBookDto.CreateRequestDto> = mutableListOf()

    /** Every update sent, as `(bookId, request)`. Empty proves a guard refused before the wire. */
    val updateRequests: MutableList<Pair<Long, CashBookDto.UpdateRequestDto>> = mutableListOf()

    /** When set, [listBooks] throws this — a list the server could not answer. */
    var listFailure: Throwable? = null

    /** What `listBooks` was last asked for, so a test can prove the dialog requests deactivated books. */
    var lastIncludeInactive: Boolean? = null
        private set

    /** Models the server: a deactivated book is withheld unless the caller asks for it. */
    override suspend fun listBooks(includeInactive: Boolean): List<CashBookDto.ResponseDto> {
        listFailure?.let { throw it }
        lastIncludeInactive = includeInactive
        return if (includeInactive) books else books.filter { it.isActive }
    }

    override suspend fun closePreview(bookId: Long): CashBookDto.ClosePreviewDto {
        closePreviewCalls++
        return preview(bookId)
    }

    override suspend fun close(
        bookId: Long,
        request: CashBookDto.CloseRequestDto,
    ): CashBookDto.CloseResponseDto {
        lastCloseRequest = request
        return CashBookDto.CloseResponseDto(
            cashBookId = bookId,
            periodStart = "2026-08-01",
            periodEnd = "2026-08-09",
            bookBalance = "128.50",
            countedBalance = request.countedBalance,
            difference = "0.00",
            closedByEmployeeId = "employee-1",
            closedAt = "2026-08-09T18:00:00",
            notes = request.notes,
        )
    }

    /** When set, [createBook] records the request and then throws this — a server refusal. */
    var createFailure: Throwable? = null

    /** When true, a created book joins [books] — as it does on the real server's next list. */
    var createJoinsList: Boolean = false

    override suspend fun createBook(request: CashBookDto.CreateRequestDto): CashBookDto.ResponseDto {
        createRequests += request
        createFailure?.let { throw it }
        val created =
            CashBookDto.ResponseDto(
                id = books.size + 1L,
                name = request.name,
                isFiscal = false,
                datevAccount = request.datevAccount,
                kost1 = request.kost1,
                kost2 = request.kost2,
                closeInterval = request.closeInterval,
                isActive = true,
                sortOrder = 0,
                currentBalance = "0.00",
                currency = "EUR",
            )
        if (createJoinsList) books = books + created
        return created
    }

    override suspend fun updateBook(
        bookId: Long,
        request: CashBookDto.UpdateRequestDto,
    ): CashBookDto.ResponseDto {
        updateRequests += bookId to request
        val existing = books.first { it.id == bookId }
        val updated =
            existing.copy(
                name = request.name,
                datevAccount = request.datevAccount,
                kost1 = request.kost1,
                kost2 = request.kost2,
                closeInterval = request.closeInterval,
                isActive = request.isActive,
                sortOrder = request.sortOrder,
            )
        // The server stores the edit: a later listBooks() must show it.
        books = books.map { if (it.id == bookId) updated else it }
        return updated
    }

    override suspend fun createTransfer(request: CashBookDto.CreateTransferRequestDto): CashBookDto.TransferResponseDto = throw NotImplementedError()

    override suspend fun reverseTransfer(
        transferId: String,
        reason: String,
    ): CashBookDto.TransferResponseDto = throw NotImplementedError()

    override suspend fun closeHistory(bookId: Long): List<CashBookDto.CloseResponseDto> = throw NotImplementedError()

    var signingCandidates: List<CashBookDto.SigningTerminalCandidateDto> = emptyList()
    val setSigningTerminalCalls = mutableListOf<Pair<Long, Long?>>()
    var setSigningTerminalResult: CashBookDto.ResponseDto? = null

    /** When set, [setSigningTerminal] records the call and then throws this — a server refusal. */
    var setSigningTerminalFailure: Throwable? = null

    override suspend fun listSigningTerminalCandidates(bookId: Long) = signingCandidates

    override suspend fun setSigningTerminal(
        bookId: Long,
        terminalId: Long?,
    ): CashBookDto.ResponseDto {
        setSigningTerminalCalls += bookId to terminalId
        setSigningTerminalFailure?.let { throw it }
        return setSigningTerminalResult ?: error("setSigningTerminalResult not set")
    }
}
