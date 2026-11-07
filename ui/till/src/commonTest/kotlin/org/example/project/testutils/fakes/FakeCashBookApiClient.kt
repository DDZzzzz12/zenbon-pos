package org.example.project.testutils.fakes

import org.example.project.core.data.remote.CashBookApiClient
import org.example.project.core.domain.dto.kassenbuch.CashBookDto

/**
 * Test double for [CashBookApiClient].
 *
 * Records what the transfer form sent, so a test can assert the exact request rather than only
 * that a call happened — an amount or a book id silently altered on the way out is precisely the
 * defect the form exists to prevent.
 */
class FakeCashBookApiClient : CashBookApiClient {
    var listBooksResult: List<CashBookDto.ResponseDto> = emptyList()
    var listBooksThrows: Throwable? = null

    var createTransferCallCount: Int = 0
        private set
    var lastTransferRequest: CashBookDto.CreateTransferRequestDto? = null
        private set
    var createTransferThrows: Throwable? = null
    var createTransferResult: CashBookDto.TransferResponseDto? = null

    override suspend fun listBooks(includeInactive: Boolean): List<CashBookDto.ResponseDto> {
        listBooksThrows?.let { throw it }
        return listBooksResult
    }

    override suspend fun createTransfer(request: CashBookDto.CreateTransferRequestDto): CashBookDto.TransferResponseDto {
        createTransferCallCount++
        lastTransferRequest = request
        createTransferThrows?.let { throw it }
        return createTransferResult ?: error("createTransferResult not set")
    }

    override suspend fun createBook(request: CashBookDto.CreateRequestDto): CashBookDto.ResponseDto = throw NotImplementedError()

    override suspend fun updateBook(
        bookId: Long,
        request: CashBookDto.UpdateRequestDto,
    ): CashBookDto.ResponseDto = throw NotImplementedError()

    override suspend fun reverseTransfer(
        transferId: String,
        reason: String,
    ): CashBookDto.TransferResponseDto = throw NotImplementedError()

    override suspend fun closePreview(bookId: Long): CashBookDto.ClosePreviewDto = throw NotImplementedError()

    override suspend fun close(
        bookId: Long,
        request: CashBookDto.CloseRequestDto,
    ): CashBookDto.CloseResponseDto = throw NotImplementedError()

    override suspend fun closeHistory(bookId: Long): List<CashBookDto.CloseResponseDto> = throw NotImplementedError()

    var signingCandidates: List<CashBookDto.SigningTerminalCandidateDto> = emptyList()
    val setSigningTerminalCalls = mutableListOf<Pair<Long, Long?>>()
    var setSigningTerminalResult: CashBookDto.ResponseDto? = null

    override suspend fun listSigningTerminalCandidates(bookId: Long) = signingCandidates

    override suspend fun setSigningTerminal(
        bookId: Long,
        terminalId: Long?,
    ): CashBookDto.ResponseDto {
        setSigningTerminalCalls += bookId to terminalId
        return setSigningTerminalResult ?: error("setSigningTerminalResult not set")
    }
}
