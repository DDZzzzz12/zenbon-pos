package org.example.project.core.domain.dto.salesinvoice

import kotlinx.serialization.Serializable
import org.example.project.money.Decimal

/**
 * Wire contract for outgoing invoices.
 *
 * **Every money field is a `String`**, never a number — the project's hard money rule. Dates are
 * ISO-8601 local date-times, matching the other DTOs in this package tree.
 */
object SalesInvoiceDto {
    @Serializable
    data class ResponseDto(
        val id: Long,
        val invoiceNumber: String?,
        val status: String,
        val revenueOrigin: String,
        val invoiceTypeCode: String,
        val precedingInvoiceId: Long?,
        val billId: Long?,
        val debtorId: Long?,
        val buyerName: String,
        val buyerVatId: String?,
        val buyerStreet: String?,
        val buyerZip: String?,
        val buyerCity: String?,
        val buyerCountry: String?,
        val buyerEmail: String?,
        val sellerName: String?,
        val sellerVatId: String?,
        val sellerTaxNumber: String?,
        val issueDate: String,
        val serviceDate: String,
        val dueDate: String?,
        val currency: String,
        val lineNetTotal: String?,
        val taxTotal: String?,
        val taxInclusiveTotal: String?,
        val prepaidAmount: String?,
        val amountDue: String?,
        val openAmount: String?,
        val notes: String?,
        /**
         * The payment terms as frozen on the invoice. Editable on a draft; the editor must be able
         * to read them back or a save-nothing round trip silently nulls them.
         */
        val paymentTermsText: String?,
        /**
         * The booking decisions' revenue account. Read from `sales_invoice_booking`, not the
         * debtor — 4d posts what the invoice carries, and master data is editable.
         */
        val revenueAccountNo: String?,
        /** The booking decisions' BU-Schlüssel, read from `sales_invoice_booking`. */
        val buCode: String?,
        val documentId: Long?,
        val issuedAt: String?,
        val cancelledAt: String?,
        val lines: List<LineDto>,
        val vatBreakdown: List<VatRowDto>,
        /** BT-109, the net after document-level allowances and charges. */
        val taxExclusiveTotal: String? = null,
        /** BT-107, Σ allowances; null when the invoice has none. */
        val allowanceTotal: String? = null,
        /** BG-20 / BG-21 in print order — a till promotion becomes an allowance. Empty when none. */
        val allowances: List<AllowanceChargeDto> = emptyList(),
    )

    @Serializable
    data class LineDto(
        val lineNo: Int,
        val itemName: String,
        val itemDescription: String?,
        val quantity: String,
        /**
         * The gross unit price the operator typed, stored verbatim. The editor uses this on reopen;
         * re-deriving it from `grossAmount / quantity` cannot round-trip (qty 3 at 3.3333 stores
         * gross 10.00 and re-derives 3.33 — a cent lost on every open-and-save).
         */
        val grossUnitPrice: String,
        val netUnitPrice: String,
        val netAmount: String,
        val vatAmount: String,
        val grossAmount: String,
        /** Percent units — `19.00`, never `0.19`. */
        val vatRate: String,
    )

    /** One EN 16931 document-level allowance (BG-20) or charge (BG-21). */
    @Serializable
    data class AllowanceChargeDto(
        val position: Int,
        val isCharge: Boolean,
        /** BT-92 / BT-99, net. */
        val amount: String,
        val vatAmount: String,
        /** The VAT-inclusive amount the till took off (or added). */
        val grossAmount: String,
        val vatCategoryCode: String,
        /** Percent units. */
        val vatRate: String,
        /** BT-97, e.g. `Aktion: Happy Hour`. */
        val reason: String?,
        /** BT-98, UNTDID 5189 — `95` Discount. */
        val reasonCode: String?,
    )

    @Serializable
    data class VatRowDto(
        /** Percent units. */
        val vatRate: String,
        val vatCategoryCode: String,
        val taxableAmount: String,
        val vatAmount: String,
    )

    /**
     * A row in the Ausgangsrechnungen list.
     *
     * Deliberately smaller than [ResponseDto]: the list renders many rows and never needs the lines
     * or the VAT breakdown, both of which are extra queries per row.
     */
    @Serializable
    data class ListItemDto(
        val id: Long,
        val invoiceNumber: String?,
        val status: String,
        val invoiceTypeCode: String,
        val issueDate: String,
        val buyerName: String,
        /** Needed by the list itself: every amount is rendered through the money formatter, which
         *  takes the row's own currency rather than assuming the shop's. */
        val currency: String,
        val taxInclusiveTotal: String?,
        val openAmount: String?,
        val hasDocument: Boolean,
    )

    @Serializable
    data class ListResponseDto(
        val items: List<ListItemDto>,
        val total: Long,
    )

    /** Create a draft invoice for a bill the guest already paid at the till. */
    @Serializable
    data class CreateFromBillRequestDto(
        val billId: Long,
        val buyerName: String,
        val buyerVatId: String? = null,
        val buyerStreet: String? = null,
        val buyerZip: String? = null,
        val buyerCity: String? = null,
        val buyerCountry: String? = null,
        val buyerEmail: String? = null,
    ) {
        fun validate(): List<String> =
            buildList {
                if (billId <= 0L) add("billId must be positive")
                if (buyerName.isBlank()) add("buyerName is required (§14 Abs. 4 Nr. 1 UStG)")
            }
    }

    /**
     * Cancel an issued invoice. The reason is printed on the Gutschrift and kept for the audit, so it
     * is mandatory and at most [MAX_REASON_LENGTH] characters — the same bound the incoming-invoice
     * cancellation uses. It stays nullable on the wire so an un-upgraded client gets a 400, not a
     * deserialisation failure.
     */
    @Serializable
    data class CancelRequestDto(
        val reason: String? = null,
    ) {
        companion object {
            /** Same width as the incoming-invoice cancellation reason (DATEV Buchungstext). */
            const val MAX_REASON_LENGTH = 60

            /** Whether [reason] is acceptable: non-blank and, trimmed, at most [MAX_REASON_LENGTH]. */
            fun isValidReason(reason: String?): Boolean {
                if (reason == null || reason.isBlank()) return false
                return reason.trim().length <= MAX_REASON_LENGTH
            }
        }
    }

    /**
     * A case-C (STANDALONE) draft: an invoice for revenue that never touched the till.
     *
     * The editor inputs **gross per line**; the server derives net and VAT and sums the BG-23
     * breakdown. Every amount is a decimal string. `revenueAccountNo`/`buCode` pre-fill the
     * `sales_invoice_booking` row (spec §3.5) — 4d resolves `bp_account_no` later.
     */
    @Serializable
    data class CreateStandaloneRequestDto(
        val debtorId: Long? = null,
        val buyerName: String,
        val buyerVatId: String? = null,
        val buyerStreet: String? = null,
        val buyerZip: String? = null,
        val buyerCity: String? = null,
        val buyerCountry: String? = null,
        val buyerEmail: String? = null,
        val serviceDate: String,
        val dueDate: String? = null,
        val paymentTermsText: String? = null,
        val revenueAccountNo: String? = null,
        val buCode: String? = null,
        val notes: String? = null,
        val lines: List<StandaloneLineInputDto>,
    ) {
        /** Field names that failed validation; empty means valid. */
        fun validate(): List<String> =
            buildList {
                if (buyerName.isBlank()) add("buyerName is required (§14 Abs. 4 Nr. 1 UStG)")
                if (serviceDate.isBlank()) add("serviceDate is required (§14 Abs. 4 Nr. 6 UStG)")
                if (lines.isEmpty()) add("lines: an invoice needs at least one line")
                lines.forEachIndexed { index, line ->
                    if (line.itemName.isBlank()) add("lines[$index].itemName is required")
                    val quantity = Decimal.parseOrNull(line.quantity)
                    if (quantity == null || quantity <= Decimal.ZERO) add("lines[$index].quantity must be positive")
                    val gross = Decimal.parseOrNull(line.grossUnitPrice)
                    if (gross == null || gross <= Decimal.ZERO) add("lines[$index].grossUnitPrice must be positive")
                    val rate = Decimal.parseOrNull(line.vatRate)
                    if (rate == null || rate < Decimal.ZERO) add("lines[$index].vatRate must not be negative")
                }
            }
    }

    /** A full replace of a draft's content — an omitted optional is written as null, like PUT /api/debtors/{id}. */
    @Serializable
    data class UpdateStandaloneRequestDto(
        val debtorId: Long? = null,
        val buyerName: String,
        val buyerVatId: String? = null,
        val buyerStreet: String? = null,
        val buyerZip: String? = null,
        val buyerCity: String? = null,
        val buyerCountry: String? = null,
        val buyerEmail: String? = null,
        val serviceDate: String,
        val dueDate: String? = null,
        val paymentTermsText: String? = null,
        val revenueAccountNo: String? = null,
        val buCode: String? = null,
        val notes: String? = null,
        val lines: List<StandaloneLineInputDto>,
    ) {
        /** Field names that failed validation; empty means valid. */
        fun validate(): List<String> =
            buildList {
                if (buyerName.isBlank()) add("buyerName is required (§14 Abs. 4 Nr. 1 UStG)")
                if (serviceDate.isBlank()) add("serviceDate is required (§14 Abs. 4 Nr. 6 UStG)")
                if (lines.isEmpty()) add("lines: an invoice needs at least one line")
                lines.forEachIndexed { index, line ->
                    if (line.itemName.isBlank()) add("lines[$index].itemName is required")
                    val quantity = Decimal.parseOrNull(line.quantity)
                    if (quantity == null || quantity <= Decimal.ZERO) add("lines[$index].quantity must be positive")
                    val gross = Decimal.parseOrNull(line.grossUnitPrice)
                    if (gross == null || gross <= Decimal.ZERO) add("lines[$index].grossUnitPrice must be positive")
                    val rate = Decimal.parseOrNull(line.vatRate)
                    if (rate == null || rate < Decimal.ZERO) add("lines[$index].vatRate must not be negative")
                }
            }
    }

    /** One typed line of a STANDALONE invoice. [grossUnitPrice] is what the editor inputs; the server derives net and VAT. */
    @Serializable
    data class StandaloneLineInputDto(
        val itemName: String,
        val itemDescription: String? = null,
        val quantity: String,
        val unitCode: String = "C62",
        val grossUnitPrice: String,
        /** Percent units — `19.00`, never `0.19`. */
        val vatRate: String,
        /** `S`/`Z` derived from the rate when absent; `AE`/`K` (reverse charge, intra-community) explicit. */
        val vatCategoryCode: String? = null,
    )

    /** Settle an open `POS_RECEIVABLE` invoice at the counter (slice 4c-2c). */
    @Serializable
    data class SettleAtTillRequestDto(
        val paymentTypeId: Long,
        /** A decimal string, never a number — the project's money rule. */
        val amount: String,
    ) {
        fun validate(): List<String> =
            buildList {
                if (paymentTypeId <= 0L) add("paymentTypeId must be positive")
                val parsed = Decimal.parseOrNull(amount)
                if (parsed == null || parsed <= Decimal.ZERO) add("amount must be positive")
            }
    }
}
