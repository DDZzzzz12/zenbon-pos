package org.example.project.testutils.fakes

import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.data.remote.KassenbuchCreateOutcome
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto

class FakeKassenbuchApiClient : KassenbuchApiClient {
    var getCurrentBalanceResult: String = "0.00"

    /** Per-book balances; a book absent from the map answers [getCurrentBalanceResult]. */
    var getCurrentBalanceByBook: Map<Long?, String> = emptyMap()
    var lastGetCurrentBalanceCashBookId: Long? = null

    /** Per-book histories; a book absent from the map answers [getEntryHistoryResult]. */
    var getEntryHistoryByBook: Map<Long?, List<KassenbuchDto.KassenbuchGroupedSummaryDto>> = emptyMap()
    var getCurrentBalanceRawResult: String = "0.00"
    var getCurrentBalanceRawCallCount: Int = 0
    var getCurrentBalanceRawThrows: Throwable? = null
    var getCashCountExpectationCallCount: Int = 0
    var getCashCountExpectationThrows: Throwable? = null
    var getCashCountExpectationResult: KassenbuchDto.CashCountExpectationDto =
        KassenbuchDto.CashCountExpectationDto(bookBalance = "0.00", unbookedCashTakings = "0.00", expectedCash = "0.00")

    /** Records what the Z-Bon flow sent, so a test can assert the count preceded the closing. */
    var cashCountCallCount: Int = 0
    var lastCashCountRequest: KassenbuchDto.CashCountRequestDto? = null
    var recordCashCountThrows: Throwable? = null
    var recordCashCountResult: KassenbuchDto.CashCountResponseDto =
        KassenbuchDto.CashCountResponseDto(
            bookBalance = "0.00",
            countedAmount = "0.00",
            difference = "0.00",
        )
    var getRecentEntriesResult: List<KassenbuchDto.KassenbuchGroupedSummaryDto> = emptyList()
    var getEntryHistoryResult: List<KassenbuchDto.KassenbuchGroupedSummaryDto> = emptyList()
    var searchEntriesResult: List<KassenbuchDto.KassenbuchGroupedSummaryDto> = emptyList()
    var getEntryByIdResult: KassenbuchDto.KassenbuchEntryResponseDto? = null
    var createEntryResult: KassenbuchDto.CreateKassenbuchEntryGroupResponseDto? = null
    var getGroupedEntryDetailResult: KassenbuchDto.KassenbuchGroupedDetailDto? = null
    var exportToDatevXmlResult: DatevExportLogDto? = null
    var exportToBuchungsstapelResult: DatevExportLogDto? = null
    var linkDocumentResult: KassenbuchDto.KassenbuchGroupedDetailDto? = null
    var unlinkDocumentResult: KassenbuchDto.KassenbuchGroupedDetailDto? = null
    var replaceDocumentResult: KassenbuchDto.KassenbuchGroupedDetailDto? = null
    var correctEntryResult: KassenbuchDto.KassenbuchGroupedDetailDto? = null

    var getCurrentBalanceThrows: Throwable? = null
    var getRecentEntriesThrows: Throwable? = null
    var getEntryHistoryThrows: Throwable? = null
    var searchEntriesThrows: Throwable? = null
    var getEntryByIdThrows: Throwable? = RuntimeException("not found")
    var createEntryThrows: Throwable? = RuntimeException("server error")
    var getGroupedEntryDetailThrows: Throwable? = RuntimeException("not found")
    var exportToDatevXmlThrows: Throwable? = RuntimeException("server error")
    var exportToBuchungsstapelThrows: Throwable? = RuntimeException("server error")
    var linkDocumentThrows: Throwable? = RuntimeException("server error")
    var unlinkDocumentThrows: Throwable? = RuntimeException("server error")
    var replaceDocumentThrows: Throwable? = RuntimeException("server error")
    var correctEntryThrows: Throwable? = null

    var getCurrentBalanceCallCount = 0
    var getRecentEntriesCallCount = 0
    var getEntryHistoryCallCount = 0
    var searchEntriesCallCount = 0
    var getEntryByIdCallCount = 0
    var createEntryCallCount = 0
    var getGroupedEntryDetailCallCount = 0
    var exportToDatevXmlCallCount = 0
    var exportToBuchungsstapelCallCount = 0
    var linkDocumentCallCount = 0
    var unlinkDocumentCallCount = 0
    var correctEntryCallCount = 0

    var lastGetEntryHistoryStartDate: String? = null
    var lastGetEntryHistoryEndDate: String? = null
    var lastGetEntryHistoryCashBookId: Long? = null
    var lastSearchQuery: String? = null
    var lastSearchCashBookIds: List<Long>? = null
    var lastCreateEntryRequest: KassenbuchDto.CreateKassenbuchEntryRequestDto? = null
    var lastGetGroupedEntryDetailHeaderId: Long? = null
    var lastLinkHeaderId: Long? = null
    var lastLinkDocumentId: Long? = null
    var lastCorrectHeaderId: Long? = null
    var lastCorrectRequest: KassenbuchDto.CorrectEntryRequestDto? = null

    override suspend fun getCurrentBalance(cashBookId: Long?): String {
        getCurrentBalanceCallCount++
        lastGetCurrentBalanceCashBookId = cashBookId
        getCurrentBalanceThrows?.let { throw it }
        return getCurrentBalanceByBook[cashBookId] ?: getCurrentBalanceResult
    }

    override suspend fun getCurrentBalanceRaw(): String {
        getCurrentBalanceRawCallCount++
        getCurrentBalanceRawThrows?.let { throw it }
        return getCurrentBalanceRawResult
    }

    /** What getAvailableCash answers, per book id (null = the fiscal Ladenkasse) (F59). */
    var availableCashByBook: Map<Long?, String> = emptyMap()
    var availableCashThrows: Throwable? = null
    val availableCashCalls = mutableListOf<Long?>()

    override suspend fun getAvailableCash(cashBookId: Long?): KassenbuchDto.AvailableCashDto {
        availableCashCalls += cashBookId
        availableCashThrows?.let { throw it }
        val available = availableCashByBook[cashBookId] ?: "0.00"
        return KassenbuchDto.AvailableCashDto(
            cashBookId = cashBookId ?: 1L,
            bookBalance = available,
            unbookedCashTakings = "0.00",
            availableCash = available,
        )
    }

    override suspend fun getCashCountExpectation(): KassenbuchDto.CashCountExpectationDto {
        getCashCountExpectationCallCount++
        getCashCountExpectationThrows?.let { throw it }
        return getCashCountExpectationResult
    }

    override suspend fun recordCashCount(request: KassenbuchDto.CashCountRequestDto): KassenbuchDto.CashCountResponseDto {
        cashCountCallCount++
        lastCashCountRequest = request
        recordCashCountThrows?.let { throw it }
        return recordCashCountResult
    }

    override suspend fun getRecentEntries(limit: Int): List<KassenbuchDto.KassenbuchGroupedSummaryDto> {
        getRecentEntriesCallCount++
        getRecentEntriesThrows?.let { throw it }
        return getRecentEntriesResult
    }

    override suspend fun getEntryHistory(
        startDate: String,
        endDate: String,
        unlinkedOnly: Boolean,
        cashBookId: Long?,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto> {
        getEntryHistoryCallCount++
        lastGetEntryHistoryStartDate = startDate
        lastGetEntryHistoryEndDate = endDate
        lastGetEntryHistoryCashBookId = cashBookId
        getEntryHistoryThrows?.let { throw it }
        return getEntryHistoryByBook[cashBookId] ?: getEntryHistoryResult
    }

    override suspend fun searchEntries(
        query: String,
        cashBookIds: List<Long>?,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto> {
        searchEntriesCallCount++
        lastSearchQuery = query
        lastSearchCashBookIds = cashBookIds
        searchEntriesThrows?.let { throw it }
        return searchEntriesResult
    }

    override suspend fun getEntryById(entryId: Long): KassenbuchDto.KassenbuchEntryResponseDto {
        getEntryByIdCallCount++
        getEntryByIdThrows?.let { throw it }
        return getEntryByIdResult ?: error("getEntryByIdResult not set")
    }

    /** When true, createEntry answers like a 202: the backdating override is waiting for a manager. */
    var createEntryAwaitsApproval = false

    override suspend fun createEntry(request: KassenbuchDto.CreateKassenbuchEntryRequestDto): KassenbuchCreateOutcome {
        createEntryCallCount++
        lastCreateEntryRequest = request
        createEntryThrows?.let { throw it }
        if (createEntryAwaitsApproval) return KassenbuchCreateOutcome.AwaitingApproval
        return KassenbuchCreateOutcome.Created(createEntryResult ?: error("createEntryResult not set"))
    }

    override suspend fun getGroupedEntryDetail(headerId: Long): KassenbuchDto.KassenbuchGroupedDetailDto {
        getGroupedEntryDetailCallCount++
        lastGetGroupedEntryDetailHeaderId = headerId
        getGroupedEntryDetailThrows?.let { throw it }
        return getGroupedEntryDetailResult ?: error("getGroupedEntryDetailResult not set")
    }

    override suspend fun exportToDatevXml(
        startDate: String,
        endDate: String,
        periodLabel: String,
    ): DatevExportLogDto {
        exportToDatevXmlCallCount++
        exportToDatevXmlThrows?.let { throw it }
        return exportToDatevXmlResult ?: error("exportToDatevXmlResult not set")
    }

    override suspend fun exportToBuchungsstapel(
        startDate: String,
        endDate: String,
        periodLabel: String,
    ): DatevExportLogDto {
        exportToBuchungsstapelCallCount++
        exportToBuchungsstapelThrows?.let { throw it }
        return exportToBuchungsstapelResult ?: error("exportToBuchungsstapelResult not set")
    }

    override suspend fun linkDocument(
        headerId: Long,
        documentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto {
        linkDocumentCallCount++
        lastLinkHeaderId = headerId
        lastLinkDocumentId = documentId
        linkDocumentThrows?.let { throw it }
        return linkDocumentResult ?: error("linkDocumentResult not set")
    }

    override suspend fun unlinkDocument(headerId: Long): KassenbuchDto.KassenbuchGroupedDetailDto {
        unlinkDocumentCallCount++
        unlinkDocumentThrows?.let { throw it }
        return unlinkDocumentResult ?: error("unlinkDocumentResult not set")
    }

    override suspend fun replaceDocument(
        headerId: Long,
        newDocumentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto {
        replaceDocumentThrows?.let { throw it }
        return replaceDocumentResult ?: error("replaceDocumentResult not set")
    }

    override suspend fun correctEntry(
        headerId: Long,
        request: KassenbuchDto.CorrectEntryRequestDto,
    ): KassenbuchDto.KassenbuchGroupedDetailDto {
        correctEntryCallCount++
        lastCorrectHeaderId = headerId
        lastCorrectRequest = request
        correctEntryThrows?.let { throw it }
        return correctEntryResult ?: error("correctEntryResult not set")
    }
}
