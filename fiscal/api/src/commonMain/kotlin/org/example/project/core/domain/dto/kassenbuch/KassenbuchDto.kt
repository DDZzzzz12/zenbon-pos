/**
 * Container object for all Kassenbuch-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and analytics
 * to maintain clear separation of concerns and consistent API structure
 * for DATEV-compliant cash book operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.kassenbuch

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.document.DocumentSummaryDto
import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.utils.datev.VatRateNormalizer
import org.example.project.money.Decimal
import org.example.project.money.sum

/**
 * Container object for all Kassenbuch-related DTOs.
 *
 * Provides organized access to all cash book data transfer objects
 * with proper validation methods for DATEV compliance.
 */
object KassenbuchDto {
    /**
     * One signed Kassenbuch entry line, as printed on the Beleg (spec §1.3):
     * goods +23.78, Warengutschein -19.11. The header entryType flips document
     * sign to cash-flow sign server-side.
     */
    @Serializable
    data class EntryLineDto(
        val kind: KassenbuchLineKind = KassenbuchLineKind.STANDARD,
        val vatRate: String? = null, // required unless NON_CASH_PAYMENT; normalized server-side
        val amount: String, // signed document amount as exact decimal string; != 0
        val belegtext: String? = null, // optional per-line text
    )

    /**
     * Summary DTO for a single Kassenbuch entry line item.
     *
     * Contains the essential fields for one entry within a grouped display.
     */
    @Serializable
    data class KassenbuchEntryLineItemDto(
        val id: Long,
        val vorzBetrag: String, // Transaction amount (positive for incoming, negative for outgoing)
        val ustSatz: String?, // VAT rate
        val belegtext: String, // Description for this specific line item
        val kind: KassenbuchLineKind = KassenbuchLineKind.STANDARD,
        val paymentTypeName: String? = null,
    )

    /**
     * Grouped summary DTO for Kassenbuch entries sharing the same belegNr.
     *
     * Used for displaying multi-VAT receipts where multiple entries share
     * the same document number. Groups entries together for card display.
     *
     * @property headerId Database ID of the header record (for fetching details)
     * @property cashBookId Cash book this header belongs to
     * @property belegNr Shared document number for all entries in this group
     * @property entryType Type of entry (all entries in group have same type)
     * @property belegtext Main description for the group
     * @property belegDatum Document date (DD.MM.YYYY format)
     * @property waehrung Currency code
     * @property totalAmount Sum of all line item amounts (exact decimal string, signed)
     * @property lineItems Individual entries with their amounts and VAT rates
     * @property currentBalance Balance after all entries in this group (exact decimal string)
     * @property employeeNumber Employee who created the entries
     * @property isLocked Whether entries are locked
     * @property createdAt Timestamp when entries were created
     * @property hasLinkedDocuments Whether this header has linked PDF documents
     * @property linkedDocumentCount Count of linked PDF documents
     */
    @Serializable
    data class KassenbuchGroupedSummaryDto(
        val headerId: Long,
        /**
         * The cash book this header is booked in. Non-null and without a default on purpose:
         * `kassenbuch_header.cash_book_id` is `NOT NULL` (V84 backfilled it, then applied the
         * constraint) and the Exposed column is a non-nullable reference, so a nullable field
         * would model a state the database cannot hold and would push a `?:` fallback into every
         * caller. Omitting the default makes the compiler point at each construction site instead
         * of letting one silently ship a placeholder book id.
         *
         * Only the id crosses the wire — the client resolves the name against the cash-book list it
         * already loads, so a renamed book never needs a second response field kept in sync.
         */
        val cashBookId: Long,
        val belegNr: String,
        val entryType: KassenbuchEntryType,
        val belegtext: String,
        val belegDatum: String,
        val waehrung: String,
        val totalAmount: String, // BigDecimal serialized as String
        val lineItems: List<KassenbuchEntryLineItemDto>,
        val currentBalance: String, // BigDecimal serialized as String
        val employeeNumber: String,
        val isLocked: Boolean = false,
        val createdAt: String,
        val hasLinkedDocuments: Boolean = false,
        val linkedDocumentCount: Int = 0,
        /**
         * ISO 4217 code the monetary fields of this response are denominated in — the shop's
         * configured currency (`ConfigKey.DEFAULT_CURRENCY`), resolved per request by the service.
         *
         * Distinct from [waehrung], which is the per-header currency column persisted with the
         * booking; this one describes how the client should render the amounts. The default only
         * keeps non-server construction sites compiling; the server mapper sets it explicitly.
         */
        val currency: String = "EUR",
    )

    /**
     * Full detail DTO for a Kassenbuch entry displayed in dialog.
     *
     * Contains complete DATEV fields, TSE information, and linked document details.
     * Used when clicking on an entry to view full details.
     *
     * @property headerId Database ID of the header record
     * @property belegNr Sequential booking number for audit trail
     * @property entryType Type of cash book entry
     * @property belegtext Document text/description
     * @property belegDatum Document date (DD.MM.YYYY format)
     * @property waehrung Currency code
     * @property totalAmount Sum of all line item amounts (exact decimal string, signed)
     * @property currentBalance Balance after this entry (exact decimal string)
     * @property employeeNumber Employee who created the entries
     * @property isLocked Whether entry is locked
     * @property createdAt Timestamp when entries were created
     * @property bu Booking key for DATEV
     * @property gegenkonto Counter account for DATEV
     * @property kost1 Cost center 1 for DATEV
     * @property kost2 Cost center 2 for DATEV
     * @property nachricht Additional message/note
     * @property tseTransactionId TSE transaction reference
     * @property tseSignature TSE digital signature
     * @property tseTransactionNumber TSE sequential number
     * @property tseSignedAt TSE signing timestamp
     * @property lineItems Individual line items with full details
     * @property hasLinkedDocuments Whether this header has linked documents
     * @property linkedDocumentCount Count of linked documents
     * @property linkedDocument Summary of the linked PDF document, if any
     */
    @Serializable
    data class KassenbuchGroupedDetailDto(
        val headerId: Long,
        val belegNr: String,
        val entryType: KassenbuchEntryType,
        val belegtext: String,
        val belegDatum: String,
        val waehrung: String,
        val totalAmount: String, // BigDecimal serialized as String
        val currentBalance: String, // BigDecimal serialized as String
        val employeeNumber: String,
        val isLocked: Boolean,
        val createdAt: String,
        // Full DATEV fields
        val bu: String?,
        val gegenkonto: String?,
        val kost1: String?,
        val kost2: String?,
        val nachricht: String?,
        // TSE fields
        val tseTransactionId: Long?,
        val tseSignature: String?,
        val tseTransactionNumber: Long?,
        val tseSignedAt: String?,
        // Line items
        val lineItems: List<KassenbuchLineItemDetailDto>,
        // Linked documents
        val hasLinkedDocuments: Boolean,
        val linkedDocumentCount: Int,
        val linkedDocument: DocumentSummaryDto? = null,
        val correctsHeaderId: Long? = null,
        val correctsBelegNr: String? = null,
        val correctionRole: CorrectionRole? = null,
        /** True if a REVERSAL correction already references this entry (can't be corrected again). */
        val alreadyReversed: Boolean = false,
        /** True on the Z-Bon aggregate entry — the client hides correction for it (§1.1). */
        val isZbonAggregate: Boolean = false,
        /**
         * Why this entry carries a Beleg date older than the shop's limit — set only when the
         * backdating override was used. Null on every ordinary entry.
         */
        val lateBookingReason: String? = null,
        /**
         * ISO 4217 code the monetary fields of this response are denominated in — the shop's
         * configured currency (`ConfigKey.DEFAULT_CURRENCY`), resolved per request by the service.
         *
         * Distinct from [waehrung], which is the per-header currency column persisted with the
         * booking; this one describes how the client should render the amounts. One field per
         * response root — [lineItems] inherit it. The default only keeps non-server construction
         * sites compiling; the server mapper sets it explicitly.
         */
        val currency: String = "EUR",
        /**
         * Groups the two legs of a cash-book transfer (`kassenbuch_header.transfer_id`). Non-null
         * only on a transfer leg; a reversal pair carries its own fresh id rather than the
         * original's, so two ids never describe one movement of money.
         */
        val transferId: String? = null,
        /**
         * Header id of the *other* leg of [transferId], resolved server-side.
         *
         * Null whenever the server could not name exactly one counter-leg — no `transferId`, no
         * sibling row, or more than one. The client must treat null as "no link to offer" and never
         * infer a sibling itself: it has no endpoint that queries by `transferId`.
         */
        val counterpartyHeaderId: Long? = null,
        /**
         * Nachsignierung record when the entry could not be signed immediately (pending, signed
         * late or abandoned). Filled by the single-entry detail read only; null otherwise.
         */
        val tseSigningNote: TseSigningNoteDto? = null,
        /**
         * Name of the cash book on the other side of the transfer
         * (`kassenbuch_header.counterparty_book_id`), denormalized so the panel can name the
         * destination without a second lookup — and so it still reads correctly for a book the
         * client's cash-book list omits (a deactivated one).
         */
        val counterpartyBookName: String? = null,
    )

    /**
     * Detail DTO for a single line item within a Kassenbuch entry.
     *
     * Contains full per-line fields including sub-balance and DATEV export line.
     *
     * @property id Line item database ID
     * @property lineNumber Position within parent header (0-indexed)
     * @property vorzBetrag Transaction amount (numeric, signed)
     * @property ustSatz VAT rate
     * @property belegtext Optional per-line description
     * @property kostmenge Cost quantity
     * @property skonto Cash discount
     * @property subBalance Running balance after this line (numeric)
     * @property datevExportLine Pre-formatted DATEV export string
     */
    @Serializable
    data class KassenbuchLineItemDetailDto(
        val id: Long,
        val lineNumber: Int,
        val vorzBetrag: String,
        val ustSatz: String?,
        val belegtext: String?,
        val kostmenge: String?,
        val skonto: String?,
        val subBalance: String,
        val kind: KassenbuchLineKind = KassenbuchLineKind.STANDARD,
        val paymentTypeName: String? = null,
        val datevExportLine: String,
    )

    /**
     * Response DTO for Kassenbuch entry data.
     *
     * Contains complete cash book entry information formatted for
     * API responses with DATEV-compliant field structure.
     * Use this for detailed entry views and export operations.
     *
     * Includes TSE (Technical Security Element) fields for German fiscal
     * compliance (KassenSichV) when available.
     */
    @Serializable
    data class KassenbuchEntryResponseDto(
        val id: Long,
        val shopId: Long,
        val entryType: KassenbuchEntryType,
        val currentBalance: String,
        // === DATEV Fields (exact specification) ===
        val waehrung: String, // Currency code (EUR, USD, etc.)
        val vorzBetrag: String, // Transaction amount (positive for incoming, negative for outgoing)
        val belegNr: String?, // Invoice/Receipt number
        val belegDatum: String, // Document date (DD.MM.YYYY format)
        val belegtext: String, // Document text/description
        val uStSatz: String?, // VAT rate
        val bu: String?, // Buchungsschlüssel (booking key)
        val gegenkonto: String?, // Counter account
        val kost1: String?, // Cost center 1
        val kost2: String?, // Cost center 2
        val kostmenge: String?, // Cost quantity
        val skonto: String?, // Cash discount
        val nachricht: String?, // Message/Note
        // === System Fields ===
        val employeeNumber: String, // UUID as string
        val createdAt: String,
        val isLocked: Boolean = false, // Whether entry is locked (from closed Z-Bon period)
        // === TSE Integration Fields (KassenSichV compliance) ===
        val tseTransactionId: Long? = null, // Reference to TSE transaction
        val tseSignature: String? = null, // Digital signature from TSE
        val tseTransactionNumber: Long? = null, // Sequential TSE transaction number
        val tseSignedAt: String? = null, // Timestamp when TSE signed this entry
        // === DATEV-specific formatted fields ===
        val datevAmountString: String, // Amount with comma decimal separator
        val datevDateString: String, // Date in DDMM format
        val formattedBookingText: String, // Complete booking text for DATEV
        val canBeExported: Boolean,
        val datevExportLine: String, // Complete DATEV export line
        /** Why the Beleg date is older than the shop's limit; set only on a backdating override. */
        val lateBookingReason: String? = null,
    )

    /**
     * Request DTO for creating new Kassenbuch entries with multi-VAT support.
     *
     * Contains all required and optional fields for creating DATEV-compliant
     * cash book entries. Supports multiple VAT line items that share the same
     * belegNr (document number), enabling receipts with mixed VAT rates.
     *
     * @property entryType Type of cash book entry (incoming/outgoing)
     * @property lines Signed, kind-tagged entry lines - at least one required
     * @property belegtext Document text/description (required)
     * @property waehrung Currency code (default: EUR)
     * @property belegDatum Document date in DD.MM.YYYY format (optional)
     * @property bu Buchungsschlüssel/booking key (optional)
     * @property gegenkonto Counter account number (optional)
     * @property kost1 Cost center 1 (optional)
     * @property kost2 Cost center 2 (optional)
     * @property kostmenge Cost quantity (optional)
     * @property skonto Cash discount (optional)
     * @property nachricht Additional message/note (optional)
     */
    @Serializable
    data class CreateKassenbuchEntryRequestDto(
        val entryType: KassenbuchEntryType,
        // === Signed, kind-tagged Entry Lines (MANDATORY) ===
        val lines: List<EntryLineDto>,
        // === DATEV Fields (exact specification) ===
        val waehrung: String = "EUR", // Currency code (EUR, USD, etc.)
        val belegDatum: String? = null, // Document date (DD.MM.YYYY format like 01.02.2026)
        val belegtext: String, // Document text/description (required)
        val bu: String? = null, // Buchungsschlüssel (booking key)
        val gegenkonto: String? = null, // Counter account
        val kost1: String? = null, // Cost center 1
        val kost2: String? = null, // Cost center 2
        val kostmenge: String? = null, // Cost quantity
        val skonto: String? = null, // Cash discount
        val nachricht: String? = null, // Message/Note
        val documentId: Long? = null, // ID of an already-uploaded document to link to this entry (required)
        /** Target cash book. Null = the shop's fiscal Ladenkasse (every pre-cash-book caller). */
        val cashBookId: Long? = null,
        /**
         * Required only when [belegDatum] is older than the shop's limit: why the receipt is booked
         * late. The override itself needs `CASHBOOK_BACKDATE` (or a manager's approval); the reason
         * is stored on the entry. Ignored — and not stored — for a date inside the limit.
         */
        val lateBookingReason: String? = null,
    ) {
        /**
         * Validation matrix, spec §1.4. Mirrored client-side for inline errors.
         *
         * @param systemLineKinds kinds the CALLER is entitled to write although they are not
         *   [KassenbuchLineKind.allowedInManualEntry] — passed only by server-internal bookings
         *   (the tip payout writes `EMPLOYEE_TIP`). Every request that reaches the server from a
         *   client is validated with the empty default, so a client can never smuggle one in.
         */
        fun validate(systemLineKinds: Set<KassenbuchLineKind> = emptySet()): List<String> {
            val errors = mutableListOf<String>()
            if (lines.isEmpty()) errors.add("At least one line is required")
            lines.forEachIndexed { index, line ->
                val n = index + 1
                val parsedAmount = Decimal.parseOrNull(line.amount)
                when {
                    parsedAmount == null -> errors.add("Line $n: amount must be a valid decimal number")
                    parsedAmount.isZero -> errors.add("Line $n: amount must not be zero")
                }
                if (!line.kind.allowedInManualEntry && line.kind !in systemLineKinds) {
                    errors.add("Line $n: ${line.kind} lines are Z-Bon-internal (booked by the system only)")
                } else if (line.kind.requiresVatRate) {
                    val rate = VatRateNormalizer.normalize(line.vatRate)
                    when {
                        rate == null && line.vatRate.isNullOrBlank() ->
                            errors.add("Line $n: VAT rate is required for this line kind")
                        rate == null -> errors.add("Line $n: VAT rate must be a valid non-negative number")
                        (rate.toDoubleOrNull() ?: -1.0) > 100.0 -> errors.add("Line $n: VAT rate must be between 0 and 100")
                    }
                }
            }
            if (lines.isNotEmpty() &&
                lines.mapNotNull { Decimal.parseOrNull(it.amount) }.sum() <= Decimal.ZERO
            ) {
                errors.add("Beleg total must be positive — choose the opposite entry type instead")
            }
            if (belegtext.isBlank()) errors.add("Belegtext is required")
            if (documentId == null || documentId <= 0L) errors.add("documentId is required and must be positive")
            if (waehrung.isBlank() || waehrung.length != 3) errors.add("Währung must be 3-character currency code (e.g., EUR)")
            kostmenge?.let { if (it.isNotBlank() && it.toDoubleOrNull() == null) errors.add("Kostmenge must be a valid decimal number") }
            skonto?.let { if (it.isNotBlank() && it.toDoubleOrNull() == null) errors.add("Skonto must be a valid decimal number") }
            return errors
        }

        /** Signed document total (= cash moved; positive by validation), as an exact decimal string. */
        fun calculateTotalAmount(): String = lines.mapNotNull { Decimal.parseOrNull(it.amount) }.sum().toPlainString()
    }

    /**
     * Response DTO for grouped Kassenbuch entry creation.
     *
     * Returned when creating entries with multiple VAT rates.
     * Contains the shared belegNr and all created entries.
     *
     * @property belegNr Shared document number for all entries
     * @property totalGrossAmount Sum of all line item amounts (signed), as an exact decimal string
     * @property entries List of created Kassenbuch entries
     * @property createdAt Timestamp when entries were created
     */
    @Serializable
    data class CreateKassenbuchEntryGroupResponseDto(
        val belegNr: String,
        val totalGrossAmount: String,
        val entries: List<KassenbuchEntryResponseDto>,
        val createdAt: String,
    )

    /**
     * Data class for creating kassenbuch entries.
     *
     * Internal data transfer object used between service and repository layers.
     * Contains all fields needed to create a single Kassenbuch database entry.
     *
     * @property entryType Type of kassenbuch entry (incoming/outgoing)
     * @property waehrung Currency code (default: EUR)
     * @property belegNr Unique document number - must be unique per shop
     * @property vorzBetrag Amount as numeric value (positive for incoming, negative for outgoing)
     * @property belegDatum Document date in DD.MM.YYYY format
* @property belegtext Description text for the entry
     */
    data class KassenbuchData(
        val entryType: KassenbuchEntryType,
        val waehrung: String = "EUR",
        val belegNr: String,
        val vorzBetrag: String,
        val belegDatum: String,
        val belegtext: String,
        val uStSatz: String? = null,
        val bu: String? = null,
        val gegenkonto: String? = null,
        val kost1: String? = null,
        val kost2: String? = null,
        val kostmenge: Double? = null,
        val skonto: Double? = null,
        val nachricht: String? = null,
        val correctsHeaderId: Long? = null,
        val correctionRole: CorrectionRole? = null,
        /** Server-set only: true on the Z-Bon aggregate entry. Never client-supplied. */
        val isZbonAggregate: Boolean = false,
        /**
         * Which cash book this entry belongs to. Null = the shop's fiscal book, which is what
         * every pre-cash-book caller means — so existing call sites need no change.
         */
        val cashBookId: Long? = null,
        /** The other side of a Geldtransit; null = Bank. */
        val counterpartyBookId: Long? = null,
        /** Shared by both legs of a transfer. */
        val transferId: String? = null,
        /** Set only when the backdating override was used — see `CreateKassenbuchEntryRequestDto`. */
        val lateBookingReason: String? = null,
    )

    /**
     * Data class for line item creation within a Kassenbuch header.
     *
     * Contains only line-specific fields - header fields (belegNr, entryType, etc.)
     * come from the parent KassenbuchData.
     *
     * @property vorzBetrag Amount as numeric value for this line (positive for incoming, negative for outgoing)
     * @property ustSatz VAT rate for this line (e.g., "19", "7", "0")
     * @property belegtext Optional per-line description (overrides header if provided)
     * @property kostmenge Optional cost quantity for this line
     * @property skonto Optional cash discount for this line
     * @property kind Line kind classification driving per-line DATEV account resolution at export
     * @property paymentTypeId Payment type behind a NON_CASH_PAYMENT line; null on legacy/backfilled rows → whole-line legacy account fallback
     */
    data class LineItemData(
        val vorzBetrag: String,
        val ustSatz: String? = null,
        val belegtext: String? = null,
        val kostmenge: Double? = null,
        val skonto: Double? = null,
        val kind: KassenbuchLineKind = KassenbuchLineKind.STANDARD,
        val paymentTypeId: Long? = null,
    )

    /**
     * Request DTO for linking a PDF document to a Kassenbuch header.
     *
     * @property documentId PDF document ID of the document to link
     */
    @Serializable
    data class LinkDocumentRequestDto(
        val documentId: Long,
    )

    /**
     * Request DTO for replacing a linked PDF document on a Kassenbuch header.
     *
     * @property newDocumentId PDF document ID of the new document to link
     */
    @Serializable
    data class ReplaceDocumentRequestDto(
        val newDocumentId: Long,
    )

    /**
     * Request to correct an existing Kassenbuch entry via Storno + corrected re-entry.
     *
     * @property reason Mandatory explanation (GoBD) — rendered on the Storno-Eigenbeleg.
     * @property correctedEntry The corrected values. Its `documentId` is ignored and
     *   overwritten server-side (copy of the original's document, or [replacementDocumentId]).
     * @property replacementDocumentId Optional already-uploaded document for the corrected
     *   re-entry; when null the original entry's document is copied.
     */
    @Serializable
    data class CorrectEntryRequestDto(
        val reason: String,
        val correctedEntry: CreateKassenbuchEntryRequestDto,
        val replacementDocumentId: Long? = null,
    )

    /**
     * What the fiscal Ladenkasse should physically hold right now: the figure a count is compared
     * against, and what the Z-Bon confirm dialog prefills.
     *
     * @property bookBalance The book's balance, as a decimal string.
     * @property unbookedCashTakings Cash from bills the book does not show yet — cash sales reach
     *   the book only as the closing's CASH_SALE aggregate.
     * @property expectedCash `bookBalance + unbookedCashTakings`.
     */
    @Serializable
    data class CashCountExpectationDto(
        val bookBalance: String,
        val unbookedCashTakings: String,
        val expectedCash: String,
    )

    /**
     * The cash a book can pay out right now — `GET /api/v1/kassenbuch/available-cash`. A withdrawal
     * (expense, Entnahme, tip payout, Geldtransit out) larger than [availableCash] is refused with
     * `409 KASSENBUCH_INSUFFICIENT_CASH` (the Kassenminus guard), and the entry forms block it.
     *
     * @property cashBookId The book asked about (the fiscal Ladenkasse when the query named none).
     * @property bookBalance The book's running balance.
     * @property unbookedCashTakings Cash takings of bills no Z-Bon has closed yet — physically in the
     *   drawer but booked only by the next closing. Non-zero for the fiscal Ladenkasse only.
     * @property availableCash `bookBalance + unbookedCashTakings`.
     */
    @Serializable
    data class AvailableCashDto(
        val cashBookId: Long,
        val bookBalance: String,
        val unbookedCashTakings: String,
        val availableCash: String,
    )

    /**
     * A drawer count taken at Z-Bon time, against the shop's fiscal Ladenkasse.
     *
     * @property countedAmount The physical cash in the till, as a decimal string — never a JSON
     *   number.
     */
    @Serializable
    data class CashCountRequestDto(
        val countedAmount: String,
    )

    /**
     * Outcome of a drawer count.
     *
     * @property bookBalance What the book said, as a decimal string.
     * @property unbookedCashTakings Cash the drawer took from bills the Kassenbuch does not show
     *   yet — cash sales reach the book only as the closing's CASH_SALE aggregate, so between two
     *   closings the drawer holds more than the book. Derived by the closing's own rule.
     * @property expectedCash `bookBalance + unbookedCashTakings`: what the drawer should hold.
     * @property countedAmount What was counted, as a decimal string.
     * @property difference `countedAmount - expectedCash`; positive means a surplus.
     * @property entryId Null when the count matched [expectedCash] exactly — the normal case,
     *   which writes nothing and consumes no belegNr.
     * @property belegNr Null for the same reason.
     */
    @Serializable
    data class CashCountResponseDto(
        val bookBalance: String,
        val countedAmount: String,
        val difference: String,
        val entryId: Long? = null,
        val belegNr: String? = null,
        val unbookedCashTakings: String = "0.00",
        val expectedCash: String = bookBalance,
    )
}
