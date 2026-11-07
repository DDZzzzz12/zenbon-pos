/**
 * Repository interface for Kassenbuch (cash book) data access operations.
 *
 * Defines the contract for DATEV-compliant cash book database operations including
 * CRUD operations, entry search, export management, and audit trail functionality.
 *
 * Uses header/line-item structure for multi-VAT support:
 * - Headers represent single logical receipts with shared DATEV fields
 * - Line items contain per-VAT amount/rate information
 *
 * Returns plain entities or nullable types; exceptions propagate to the service layer.
 */
package org.example.project.features.kassenbuch.domain.repository

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.tse.domain.models.TseTransaction

/**
 * Repository interface for Kassenbuch (cash book) data access operations.
 *
 * Provides contract for DATEV-compliant cash book database operations with
 * support for automated entry generation, export tracking, and German
 * accounting compliance requirements.
 *
 * All methods work with [KassenbuchHeader] entities which contain line items.
 */
interface KassenbuchRepository {
    /**
     * Creates a new kassenbuch entry with header and line items.
     *
     * @param shop Shop entity reference
     * @param employee Employee entity reference
     * @param headerData Shared header fields
     * @param lineItemsData List of line item data (one per VAT rate)
     * @param tseTransaction Optional TSE transaction for German fiscal compliance
     * @return Created header (including line items)
     */
    fun createEntry(
        shop: ShopInfo,
        employee: Employee,
        headerData: KassenbuchDto.KassenbuchData,
        lineItemsData: List<KassenbuchDto.LineItemData>,
        tseTransaction: TseTransaction? = null,
    ): KassenbuchHeader

    /**
     * Gets the current cash balance for a shop.
     *
     * @param shop Shop entity reference
     * @param cashBookId Which cash book to scope to; null = the shop's fiscal Ladenkasse
     * @return Balance as BigDecimal (zero if no entries exist)
     */
    fun getCurrentBalance(
        shop: ShopInfo,
        cashBookId: Long? = null,
    ): java.math.BigDecimal

    /**
     * Gets kassenbuch headers within a date range.
     *
     * @param shop Shop entity reference
     * @param startDate Start date in ISO format (YYYY-MM-DD)
     * @param endDate End date in ISO format (YYYY-MM-DD)
     * @param unlinkedOnly If true, only return entries without a linked document
     * @param cashBookIds When non-null, restricts results to headers whose cash book id is in
     *        this list. `null` (the default) returns entries from ALL of the shop's cash books —
     *        the complete Buchungsstapel most DATEV export callers need. Passing an empty list
     *        is honoured literally (yields no rows), it is NOT treated as "no filter".
     * @return List of headers
     */
    fun getEntriesByDateRange(
        shop: ShopInfo,
        startDate: String,
        endDate: String,
        unlinkedOnly: Boolean = false,
        cashBookIds: List<Long>? = null,
    ): List<KassenbuchHeader>

    /**
     * Text lookup across ALL periods — the "search everywhere" escape for when the operator knows
     * a Belegnummer or a Belegtext but not which period the entry sits in. Deliberately unbounded
     * in time, which is exactly what distinguishes it from [getEntriesByDateRange].
     *
     * Matches [query] as a substring of belegNr **or** belegtext, case-insensitively (both sides
     * are lower-cased, so it behaves identically on H2 and PostgreSQL). Because the match is a
     * SQL `LIKE`, a `%` or `_` typed by the operator acts as a wildcard rather than a literal —
     * harmless here (the value is still bound as a parameter, so this is not an injection vector)
     * and occasionally useful, but it does mean `50%` matches more than the operator may expect.
     *
     * @param shop Shop entity reference. Shop-scoped on purpose — a cash book id is not a tenancy
     *        boundary, so scoping by book alone would let one shop read another's entries.
     * @param query Search term; callers are expected to reject a blank one before calling.
     * @param cashBookIds When non-null, restricts results to headers whose cash book id is in this
     *        list. `null` (the default) searches ALL of the shop's cash books. An empty list is
     *        honoured literally (yields no rows), it is NOT treated as "no filter".
     * @param limit Hard cap on the number of headers returned — a deliberate cap, not a page size:
     *        there is no continuation token, so a caller that hits it is seeing a truncated result
     *        set and should tell the operator to narrow the search rather than imply "no more
     *        results".
     * @return Matching headers ordered by createdAt descending (newest first)
     */
    fun searchEntries(
        shop: ShopInfo,
        query: String,
        cashBookIds: List<Long>? = null,
        limit: Int = 100,
    ): List<KassenbuchHeader>

    /**
     * Gets a specific kassenbuch header by ID.
     *
     * @param headerId Header ID to retrieve
     * @return Header or null if not found
     */
    fun getEntryById(headerId: Long): KassenbuchHeader?

    /**
     * Returns true if [headerId] already has a REVERSAL correction entry pointing at it.
     * Used to prevent double-storno.
     */
    fun hasReversal(headerId: Long): Boolean

    /**
     * Returns all correction entries (REVERSAL and RE_ENTRY) whose correctsHeaderId == [headerId].
     */
    fun findCorrectionsFor(headerId: Long): List<KassenbuchHeader>

    /**
     * Gets a kassenbuch header by belegNr.
     *
     * @param belegNr Document number to search (YYYY-MM-NNNN format)
     * @return Header or null if not found
     */
    fun getEntryByBelegNr(belegNr: String): KassenbuchHeader?

    /**
     * Gets the next available belegNr for the current month.
     *
     * @param cashBookId Which cash book to scope to; null = the shop's fiscal Ladenkasse
     * @return Next sequential belegNr in YYYY-MM-NNNN format
     */

    /**
     * The next belegNr in [cashBookId]'s own monthly series, or the shop's FISCAL book's series
     * when [cashBookId] is null.
     *
     * Takes the shop — rather than defaulting to a global max — because each cash book keeps its
     * own continuous Belegnummernkreis. Sharing one pool across books leaves each book's numbering
     * gapped wherever the other consumed a number, which is precisely what an auditor questions.
     * Mirrors [getCurrentBalance]'s `(shop, cashBookId)` shape.
     */
    suspend fun getNextBelegNr(
        shop: ShopInfo,
        cashBookId: Long? = null,
    ): String

    /**
     * Links a PDF document to a Kassenbuch header.
     *
     * @param headerId Kassenbuch header ID
     * @param pdfDocumentId PDF document ID to link
     * @return Updated header
     * @throws IllegalArgumentException if header or document not found, or already linked
     */
    fun linkDocument(
        headerId: Long,
        pdfDocumentId: Long,
    ): KassenbuchHeader

    /**
     * Removes the linked document from a Kassenbuch header.
     *
     * @param headerId Kassenbuch header ID
     * @return Updated header
     * @throws IllegalArgumentException if header not found
     */
    fun unlinkDocument(headerId: Long): KassenbuchHeader

    /**
     * Gets the cash balance at a specific point in time.
     *
     * Returns the currentBalance of the most recent KassenbuchHeader whose
     * createdAt is strictly before [at], or BigDecimal.ZERO if none exists.
     *
     * @param at Point in time (exclusive upper bound)
     * @param cashBookId Which cash book to scope to; null = across all books
     * @return Balance as BigDecimal
     */
    fun getBalanceAt(
        at: kotlinx.datetime.LocalDateTime,
        cashBookId: Long? = null,
    ): java.math.BigDecimal

    /**
     * Gets all KassenbuchHeaders whose createdAt falls in [start, end).
     *
     * Used by the monthly-close Kassenbuch roll-up.
     *
     * @param start Window start (inclusive)
     * @param end Window end (exclusive)
     * @param cashBookId The one book to read — entries are per book, never across books
     * @return List of KassenbuchHeader entities ordered by createdAt ascending
     */
    fun findEntriesInWindow(
        start: kotlinx.datetime.LocalDateTime,
        end: kotlinx.datetime.LocalDateTime,
        cashBookId: Long,
    ): List<KassenbuchHeader>

    /**
     * Gets the most recently created Kassenbuch headers for a shop.
     *
     * Used by the admin dashboard to show the latest cash book entries.
     *
     * @param shop Shop entity reference
     * @param limit Maximum number of headers to return
     * @param cashBookId When non-null, restricts results to this cash book. `null` (the default)
     *        returns entries from ALL of the shop's cash books.
     * @return List of KassenbuchHeader entities ordered by createdAt descending
     */
    fun getRecentEntries(
        shop: ShopInfo,
        limit: Int,
        cashBookId: Long? = null,
    ): List<KassenbuchHeader>

    /**
     * Replaces the linked document on a header, leaving the old Document row intact.
     *
     * Unlike [linkDocument], this method allows the header to already have a linked
     * document. The old document row is not deleted — only the FK is overwritten —
     * so audit trail is preserved.
     *
     * @param headerId Kassenbuch header ID
     * @param newDocumentId New document ID to link
     * @return Updated header
     * @throws IllegalArgumentException if header or document not found, or new document
     *         is already linked to a different header
     */
    fun replaceDocument(
        headerId: Long,
        newDocumentId: Long,
    ): KassenbuchHeader

    /**
     * Counts Kassenbuch headers in [start, end) that have no linked document.
     *
     * Used by the monthly-close checklist to detect cash movements that lack
     * supporting documentation (e.g., receipts or invoices).
     *
     * @param start Window start (inclusive, compared against createdAt)
     * @param end Window end (exclusive, compared against createdAt)
     * @return Count of undocumented entries in the window
     */
    fun countUndocumentedInWindow(
        start: kotlinx.datetime.LocalDateTime,
        end: kotlinx.datetime.LocalDateTime,
    ): Int
}
