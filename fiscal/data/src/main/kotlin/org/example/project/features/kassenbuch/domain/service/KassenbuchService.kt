/**
 * Service interface for Kassenbuch (cash book) business logic operations.
 *
 * Defines the contract for DATEV-compliant cash book business operations including
 * entry management, validation, export preparation, and coordination with repository
 * layer. Handles German accounting business rules and data transformation for
 * cash book operations.
 *
 * Methods throw [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from `Result<T, Error>` in Stage B Batch 3.
 */
package org.example.project.features.kassenbuch.domain.service

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto

/**
 * Service interface for Kassenbuch (cash book) business logic operations.
 *
 * Provides high-level business operations for DATEV-compliant cash book
 * management, including entry creation, validation, and export preparation
 * according to German GoBD requirements.
 */
interface KassenbuchService {
    /**
     * Creates new kassenbuch entries with multi-VAT support.
     *
     * Creates one or more database entries sharing the same belegNr (document number).
     * Each VAT line item in the request creates a separate entry with its own
     * amount and VAT rate, enabling receipts with mixed VAT rates.
     *
     * @param employee Employee ID creating the entries
     * @param deviceId Device ID for TSE client lookup
     * @param request Entry creation request with VAT line items
     * @return Grouped response containing all created entries
     */
    suspend fun createEntry(
        employee: String,
        deviceId: String,
        request: KassenbuchDto.CreateKassenbuchEntryRequestDto,
    ): KassenbuchDto.CreateKassenbuchEntryGroupResponseDto

    /**
     * True when [request]'s Beleg date is past the shop's limit but within the override ceiling —
     * the case that needs a reason and `CASHBOOK_BACKDATE` (or a manager's approval). The route asks
     * this before deciding how the request must be authorised.
     */
    suspend fun needsLateBookingOverride(request: KassenbuchDto.CreateKassenbuchEntryRequestDto): Boolean

    /**
     * [createEntry] for an **authorised** backdating override: the caller holds `CASHBOOK_BACKDATE`
     * or a manager approved the request. A Beleg date past the limit is then accepted with
     * `request.lateBookingReason`, which is stored on the entry. Every other rule still applies.
     */
    suspend fun createBackdatedEntry(
        employee: String,
        deviceId: String,
        request: KassenbuchDto.CreateKassenbuchEntryRequestDto,
    ): KassenbuchDto.CreateKassenbuchEntryGroupResponseDto

    /**
     * Get current cash balance for a shop.
     *
     * Calculates the current balance by summing all kassenbuch entries.
     *
     * @param cashBookId Which cash book to scope to; `null` (the default) resolves to the shop's
     *        FISCAL book. Unlike [getEntryHistory]/[getRecentEntries], a balance is never summed
     *        across books — combining a till and a safe would yield a number that matches no
     *        physical count and no DATEV account.
     * @return The balance as a canonical decimal string (e.g. `"1234.56"`), like every other money
     *         field on the wire. It used to be the German display text (`"1.234,56"`), which no money
     *         parser accepts (admin16 NOTE-5); clients format it themselves.
     */
    suspend fun getCurrentBalance(cashBookId: Long? = null): String

    /**
     * Get kassenbuch entry history for a date range, grouped by belegNr.
     *
     * @param cashBookId When non-null, restricts the history to this cash book. `null` (the
     *        default) returns entries from ALL of the shop's cash books.
     */
    suspend fun getEntryHistory(
        startDate: String,
        endDate: String,
        unlinkedOnly: Boolean = false,
        cashBookId: Long? = null,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto>

    /**
     * Gets the most recently created kassenbuch entries for the default shop.
     *
     * @param limit Maximum number of entries to return
     * @param cashBookId When non-null, restricts results to this cash book. `null` (the default)
     *        returns entries from ALL of the shop's cash books.
     * @return Grouped summaries ordered by createdAt descending
     */
    suspend fun getRecentEntries(
        limit: Int,
        cashBookId: Long? = null,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto>

    /**
     * Text search across ALL periods, for when the operator knows a Belegnummer or Belegtext but
     * not which period the entry sits in. Unlike [getEntryHistory] this takes no date bounds.
     *
     * @param query Search term matched against belegNr or belegtext, case-insensitively. Callers
     *        must reject a blank term — an empty search would just return the newest [limit]
     *        entries and read to the operator as a broken filter.
     * @param cashBookIds When non-null, restricts results to these cash books. `null` (the
     *        default) searches ALL of the shop's cash books.
     * @param limit Hard cap on the number of results — a cap, not a page size. There is no
     *        continuation token, so hitting it means the result set was truncated.
     * @return Grouped summaries ordered by createdAt descending
     */
    suspend fun searchEntries(
        query: String,
        cashBookIds: List<Long>? = null,
        limit: Int = 100,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto>

    /**
     * Get a specific kassenbuch entry by ID.
     */
    suspend fun getEntryById(entryId: Long): KassenbuchDto.KassenbuchEntryResponseDto

    /**
     * Get grouped entry detail for dialog display.
     */
    suspend fun getGroupedEntryDetail(headerId: Long): KassenbuchDto.KassenbuchGroupedDetailDto

    /**
     * Links a PDF document to a Kassenbuch header entry.
     */
    suspend fun linkDocument(
        employeeId: String,
        deviceId: String,
        headerId: Long,
        documentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto

    /**
     * Removes the linked document from a Kassenbuch header entry.
     */
    suspend fun unlinkDocument(
        employeeId: String,
        deviceId: String,
        headerId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto

    /**
     * Balance of the shop's FISCAL cash book (the Ladenkasse) at a point in time: the currentBalance
     * of its most recent header created strictly before [at], or zero. Balances are per book, never
     * across books — reading "whichever book booked last" reported the Tresor's balance as the
     * Monatsabschluss closing (E2E-2026-09-18 #9).
     */
    suspend fun getFiscalBookBalanceAt(at: kotlinx.datetime.LocalDateTime): java.math.BigDecimal

    /**
     * The shop's FISCAL cash book headers whose createdAt falls in [start, end).
     */
    suspend fun findFiscalBookEntriesInWindow(
        start: kotlinx.datetime.LocalDateTime,
        end: kotlinx.datetime.LocalDateTime,
    ): List<org.example.project.features.kassenbuch.domain.models.KassenbuchHeader>

    /**
     * Replaces the linked PDF document on a Kassenbuch header.
     *
     * Soft-replace: the previously linked Document row is retained for audit;
     * only the header FK is overwritten. Rejected if the header is locked
     * (Z-Bon closed / exported).
     */
    suspend fun replaceDocument(
        employeeId: String,
        deviceId: String,
        headerId: Long,
        newDocumentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto

    /**
     * Corrects an existing entry via Storno + corrected re-entry.
     *
     * Appends two new entries in the current open period (dated today): a REVERSAL that
     * cancels [headerId] (with an auto-generated Storno-Eigenbeleg stating [request].reason
     * and referencing the original), and a RE_ENTRY with the corrected values. The original
     * entry is never mutated. Allowed on locked entries. Returns the grouped detail of the
     * corrected re-entry.
     */
    suspend fun createCorrection(
        employeeId: String,
        deviceId: String,
        headerId: Long,
        request: KassenbuchDto.CorrectEntryRequestDto,
    ): KassenbuchDto.KassenbuchGroupedDetailDto

    /**
     * Records a drawer count against the shop's FISCAL Ladenkasse and books the difference.
     *
     * The count is compared against what the drawer should physically hold: the book balance
     * PLUS the cash of bills not yet closed (see [getCashCountExpectation]) — cash sales reach the
     * book only as the Z-Bon's aggregate, booked after this count.
     *
     * A count that matches that expectation writes nothing — no entry, no Eigenbeleg, no belegNr
     * consumed. A difference becomes one CASH_SURPLUS (counted > expected) or CASH_SHORTAGE
     * (counted < expected) entry, TSE-signed with an auto-generated Eigenbeleg: a till difference
     * is a real Geschäftsvorfall (DifferenzSollIst) under §146a, unlike the non-fiscal Kassensturz
     * difference which [CashBookCloseService] marks NOT_APPLICABLE.
     *
     * Call this BEFORE creating the Z-Bon, so the closing's sweep picks the entry up into the
     * closing that caused it.
     *
     * @param employeeId Employee recording the count
     * @param deviceId Device ID for TSE client lookup — signing needs a terminal
     * @param request The counted amount
     */
    suspend fun recordCashCount(
        employeeId: String,
        deviceId: String,
        request: KassenbuchDto.CashCountRequestDto,
    ): KassenbuchDto.CashCountResponseDto

    /**
     * What the fiscal Ladenkasse should physically hold right now — the figure [recordCashCount]
     * compares a count against, and what a client prefills the count field with.
     *
     * `expectedCash = bookBalance + unbookedCashTakings`, where the takings are the cash of every
     * bill not yet attributed to a closing, derived by the closing's own aggregate rule.
     */
    suspend fun getCashCountExpectation(): KassenbuchDto.CashCountExpectationDto

    /**
     * What cash book [cashBookId] (null = the fiscal Ladenkasse) can pay out right now — the figure
     * the Kassenminus guard (F59) refuses a withdrawal against: its running balance, plus, for the
     * fiscal Ladenkasse, the cash takings of bills no Z-Bon has closed yet. 404 for a book that is
     * not the shop's.
     */
    suspend fun getAvailableCash(cashBookId: Long?): KassenbuchDto.AvailableCashDto

    /**
     * Hands [request]'s amount of an employee's tips (TrinkgeldAN) from closing [zBonId] to them in
     * cash: one signed `CASH_WITHDRAWAL` out of the fiscal book with a single `EMPLOYEE_TIP` line
     * (DSFinV-K `TrinkgeldAN`, key 5; DATEV `special[EMPLOYEE_TIP]`), plus an `employee_tip_payout`
     * row linking it to (closing, employee). Dated now — it belongs to the period after the closing.
     *
     * Refused (nothing booked) when the amount is not positive or exceeds what is still payable —
     * the employee's tip total in that closing minus every payout already booked against it — when
     * the closing or employee does not exist, or when the employee holds no tip there. Partial
     * payouts are allowed. A cash payout larger than the drawer holds is refused 409
     * `KASSENBUCH_INSUFFICIENT_CASH` (the Kassenminus guard).
     *
     * With `method = BANK_PAYROLL` (F59, option C) nothing is booked in the Kassenbuch: only the
     * `employee_tip_payout` row, which clears the open amount — the tip was paid by bank transfer or
     * payroll. No DSFinV-K row, no DATEV booking, no TSE signature, no terminal needed. Audited as
     * `SETTLE_EMPLOYEE_TIP_BANK_PAYROLL`.
     *
     * @param employeeId the OPERATOR booking the payout (from the token)
     * @param deviceId the till — signing needs its terminal, like any fiscal Kassenbuch entry
     */
    suspend fun payOutEmployeeTip(
        employeeId: String,
        deviceId: String,
        zBonId: Long,
        request: ZBonDto.CreateTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto

    /**
     * Stornos the tip payout booked as Kassenbuch entry [payoutHeaderId] against closing [zBonId]:
     * an append-only, signed `CORRECTION_IN` entry (role REVERSAL, `correctsHeader` = the payout) with
     * one `EMPLOYEE_TIP` line of +amount and an Eigenbeleg naming [request]'s reason. The amount is
     * payable again for that employee. The payout entry is never edited.
     *
     * 409 when the entry is no tip payout of that closing or was already stornoed; 404 for an
     * unknown closing; 400 for a blank reason.
     */
    suspend fun reverseEmployeeTipPayout(
        employeeId: String,
        deviceId: String,
        zBonId: Long,
        payoutHeaderId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto

    /**
     * Stornos the BANK_PAYROLL settlement [payoutId] of closing [zBonId] (F59, option C): an
     * append-only reversal row in `employee_tip_payout`, no Kassenbuch entry. The amount is open
     * again. Same rules as a cash payout's storno: a reason is required, once only. 409
     * `ZBON_TIP_PAYOUT_NOT_A_PAYOUT` for a row that is not a bank/payroll settlement of that closing
     * (a cash payout is stornoed through [reverseEmployeeTipPayout]), 409
     * `ZBON_TIP_PAYOUT_ALREADY_REVERSED` for a second storno. Audited as
     * `REVERSE_EMPLOYEE_TIP_SETTLEMENT`.
     */
    suspend fun reverseEmployeeTipSettlement(
        employeeId: String,
        deviceId: String,
        zBonId: Long,
        payoutId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto
}
