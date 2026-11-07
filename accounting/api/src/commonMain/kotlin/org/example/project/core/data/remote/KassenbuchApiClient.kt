/**
 * API client interface for Kassenbuch (cash book) operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto

interface KassenbuchApiClient {
    companion object {
        /**
         * The hard cap the server applies to [searchEntries] — a cap, not a page size, and the
         * route exposes no way to change it. Callers compare the result count against this to tell
         * the operator the list was truncated; there is no continuation token.
         */
        const val SEARCH_RESULT_LIMIT: Int = 100
    }

    /**
     * A book's balance as a canonical decimal string (e.g. `"1234.56"`) — format it for display with
     * `formatMoneyForDisplay`. A server older than admin16 NOTE-5 sends the German display text
     * (`"1.234,56"`); the implementation normalizes that, so callers always get the canonical form.
     *
     * @param cashBookId The book to ask about. `null` = the shop's fiscal Ladenkasse, which is what
     *        the server defaults to — it never sums across books.
     */
    suspend fun getCurrentBalance(cashBookId: Long? = null): String

    /**
     * The fiscal book's balance as a canonical decimal string (e.g. `"1234.56"`).
     *
     * Equivalent to `getCurrentBalance(null)` since the server answers canonical decimals by
     * default; it asks with the legacy `?raw=true` switch, which older servers needed for that form.
     */
    suspend fun getCurrentBalanceRaw(): String

    /**
     * What the fiscal Ladenkasse should physically hold right now: the book balance PLUS the cash
     * of bills not yet closed, which the book shows only after the Z-Bon books its aggregate.
     * Prefill a count field with `expectedCash` — the bare book balance is short by the day's
     * takings, and a count against it books those takings as a phantom surplus.
     */
    suspend fun getCashCountExpectation(): KassenbuchDto.CashCountExpectationDto

    /**
     * What cash book [cashBookId] (null = the fiscal Ladenkasse) can pay out right now — the ceiling
     * the server's Kassenminus guard refuses a withdrawal above: `GET /api/v1/kassenbuch/available-cash`.
     */
    suspend fun getAvailableCash(cashBookId: Long? = null): KassenbuchDto.AvailableCashDto

    /**
     * Records a drawer count against the fiscal Ladenkasse and books any difference.
     *
     * The response's `entryId` is null when the count matched the book — nothing was written.
     * Call this BEFORE creating the Z-Bon so the closing sweeps the difference into itself.
     */
    suspend fun recordCashCount(request: KassenbuchDto.CashCountRequestDto): KassenbuchDto.CashCountResponseDto

    suspend fun getRecentEntries(limit: Int): List<KassenbuchDto.KassenbuchGroupedSummaryDto>

    /**
     * @param cashBookId When non-null, restricts the history to that single cash book. `null`
     *        returns every book's entries — what every pre-cash-book caller means.
     */
    suspend fun getEntryHistory(
        startDate: String,
        endDate: String,
        unlinkedOnly: Boolean = false,
        cashBookId: Long? = null,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto>

    /**
     * Text search across ALL periods, for when the operator knows a Belegnummer or Belegtext but
     * not which period the entry sits in. Takes no date bounds.
     *
     * @param query Matched against belegNr or belegtext, case-insensitively. Must not be blank —
     *        the server answers 400 for a blank term, so callers guard before calling.
     * @param cashBookIds When non-null, restricts results to these books; `null` searches all of
     *        them. Sent as ONE comma-separated `cashBookIds` parameter — the server splits on
     *        `,` and reads only the first occurrence of the parameter, so repeating it would
     *        silently drop every id but the first.
     * @return At most [SEARCH_RESULT_LIMIT] grouped summaries, newest first.
     */
    suspend fun searchEntries(
        query: String,
        cashBookIds: List<Long>? = null,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto>

    suspend fun getEntryById(entryId: Long): KassenbuchDto.KassenbuchEntryResponseDto

    /** Books an entry — or, for a backdating override without `CASHBOOK_BACKDATE`, asks a manager (202). */
    suspend fun createEntry(request: KassenbuchDto.CreateKassenbuchEntryRequestDto): KassenbuchCreateOutcome

    suspend fun getGroupedEntryDetail(headerId: Long): KassenbuchDto.KassenbuchGroupedDetailDto

    suspend fun exportToDatevXml(
        startDate: String,
        endDate: String,
        periodLabel: String = "",
    ): DatevExportLogDto

    suspend fun exportToBuchungsstapel(
        startDate: String,
        endDate: String,
        periodLabel: String = "",
    ): DatevExportLogDto

    suspend fun linkDocument(
        headerId: Long,
        documentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto

    suspend fun unlinkDocument(headerId: Long): KassenbuchDto.KassenbuchGroupedDetailDto

    suspend fun replaceDocument(
        headerId: Long,
        newDocumentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto

    suspend fun correctEntry(
        headerId: Long,
        request: KassenbuchDto.CorrectEntryRequestDto,
    ): KassenbuchDto.KassenbuchGroupedDetailDto
}
