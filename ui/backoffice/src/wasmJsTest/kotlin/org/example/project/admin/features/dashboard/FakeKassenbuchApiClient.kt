// ui/backoffice/src/wasmJsTest/kotlin/org/example/project/admin/features/dashboard/FakeKassenbuchApiClient.kt
package org.example.project.admin.features.dashboard

import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.data.remote.KassenbuchCreateOutcome
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto

class FakeKassenbuchApiClient(
    private val entries: () -> List<KassenbuchDto.KassenbuchGroupedSummaryDto>,
    private val balance: () -> String,
    /**
     * The detail behind a header id. Defaults to "unsupported" so the many tests that never open a
     * detail keep failing loudly if they accidentally do; tests about the detail panel supply one.
     */
    private val detail: (Long) -> KassenbuchDto.KassenbuchGroupedDetailDto = { throw NotImplementedError() },
) : KassenbuchApiClient {
    /** Records the startDate the VM passed to getEntryHistory, so tests can assert its format. */
    var lastEntryHistoryStart: String? = null
        private set

    /** Records the cashBookId filter the VM passed, so tests can assert the "Alle Kassen" tab sends none. */
    var lastEntryHistoryCashBookId: Long? = null
        private set

    var lastRecentEntriesLimit: Int = 0
        private set

    /**
     * How many times the recent entries were fetched — a cached list is only distinguishable from a
     * re-read by the count, not by what the state holds afterwards.
     */
    var recentEntriesCalls: Int = 0
        private set

    var lastSearchQuery: String? = null
        private set

    var lastSearchCashBookIds: List<Long>? = null
        private set

    /**
     * Counts every createEntry call, not just the last request.
     *
     * A guard that hides a button while still calling the server is only distinguishable from a real
     * one by asserting that **no request was made at all** — see `CreateEntryGuardTest`.
     */
    var createEntryCalls: Int = 0
        private set

    var lastCreateRequest: KassenbuchDto.CreateKassenbuchEntryRequestDto? = null
        private set

    override suspend fun getCurrentBalance(cashBookId: Long?): String = balance()

    // adminApp has no terminal, so it can neither count a till nor prefill one — these exist
    // only to satisfy the interface.
    override suspend fun getCurrentBalanceRaw(): String = balance()

    override suspend fun getAvailableCash(cashBookId: Long?): KassenbuchDto.AvailableCashDto =
        KassenbuchDto.AvailableCashDto(
            cashBookId = cashBookId ?: 1L,
            bookBalance = balance(),
            unbookedCashTakings = "0.00",
            availableCash = balance(),
        )

    override suspend fun getCashCountExpectation(): KassenbuchDto.CashCountExpectationDto =
        KassenbuchDto.CashCountExpectationDto(bookBalance = balance(), unbookedCashTakings = "0.00", expectedCash = balance())

    override suspend fun recordCashCount(request: KassenbuchDto.CashCountRequestDto): KassenbuchDto.CashCountResponseDto =
        throw NotImplementedError("adminApp does not record drawer counts")

    override suspend fun getRecentEntries(limit: Int): List<KassenbuchDto.KassenbuchGroupedSummaryDto> {
        lastRecentEntriesLimit = limit
        recentEntriesCalls++
        return entries()
    }

    override suspend fun getEntryHistory(
        startDate: String,
        endDate: String,
        unlinkedOnly: Boolean,
        cashBookId: Long?,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto> {
        lastEntryHistoryStart = startDate
        lastEntryHistoryCashBookId = cashBookId
        return entries()
    }

    override suspend fun searchEntries(
        query: String,
        cashBookIds: List<Long>?,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto> {
        lastSearchQuery = query
        lastSearchCashBookIds = cashBookIds
        return entries()
    }

    override suspend fun getEntryById(entryId: Long): KassenbuchDto.KassenbuchEntryResponseDto = throw NotImplementedError()

    /** When true, createEntry answers like a 202: the backdating override is waiting for a manager. */
    var createEntryAwaitsApproval = false

    override suspend fun createEntry(request: KassenbuchDto.CreateKassenbuchEntryRequestDto): KassenbuchCreateOutcome {
        createEntryCalls++
        lastCreateRequest = request
        if (createEntryAwaitsApproval) return KassenbuchCreateOutcome.AwaitingApproval
        return KassenbuchCreateOutcome.Created(
            KassenbuchDto.CreateKassenbuchEntryGroupResponseDto(
                belegNr = "KB-2026-0001",
                totalGrossAmount = request.calculateTotalAmount(),
                entries = emptyList(),
                createdAt = "2026-08-10T09:00:00",
            ),
        )
    }

    override suspend fun getGroupedEntryDetail(headerId: Long): KassenbuchDto.KassenbuchGroupedDetailDto = detail(headerId)

    override suspend fun exportToDatevXml(
        startDate: String,
        endDate: String,
        periodLabel: String,
    ): DatevExportLogDto = throw NotImplementedError()

    override suspend fun exportToBuchungsstapel(
        startDate: String,
        endDate: String,
        periodLabel: String,
    ): DatevExportLogDto = throw NotImplementedError()

    override suspend fun linkDocument(
        headerId: Long,
        documentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto = throw NotImplementedError()

    override suspend fun unlinkDocument(headerId: Long): KassenbuchDto.KassenbuchGroupedDetailDto = throw NotImplementedError()

    override suspend fun replaceDocument(
        headerId: Long,
        newDocumentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto = throw NotImplementedError()

    override suspend fun correctEntry(
        headerId: Long,
        request: KassenbuchDto.CorrectEntryRequestDto,
    ): KassenbuchDto.KassenbuchGroupedDetailDto = throw NotImplementedError()
}
