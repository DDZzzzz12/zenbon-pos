package org.example.project.core.domain.dto.bill

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.dto.tse.TseReceiptDataDto
import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.StornoReason

/**
 * Data Transfer Objects for Bill API operations.
 *
 * Container object for all Bill-related DTOs including request validation,
 * response formatting, and data transformation utilities. Follows the standard
 * object container pattern for organized DTO management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
object BillDto {
    /**
     * Response DTO for bill data.
     *
     * Used for API responses when returning bill information to clients.
     * Contains all public fields from the entity in a serializable format.
     */
    @Serializable
    data class BillResponseDto(
        /** Bill unique identifier */
        val billId: Long,
        /** Employee ID who created the bill */
        val employeeId: String,
        val employeeNumber: String,
        /** Who receives the tip from this bill */
        val tipRecipient: String, // TipRecipient enum as string
        /** Table session ID where the bill was generated */
        val tableSessionId: Long,
        val tableNumber: String,
        /** Unique bill identifier for business operations */
        val billNumber: String,
        val billAmount: String,
        /** Total discount amount applied to items in this bill */
        val discountAmount: String = "0.00",
        /** Total promotional deduction amount */
        val promotionAmount: String = "0.00",
        /** Applied promotions on this bill */
        val promotions: List<PromotionDto.BillPromotionResponseDto> = emptyList(),
        /** Total bill amount */
        val paidAmount: String, // Converted from BigDecimal for JSON compatibility
        /** Tip amount */
        val tips: String, // Converted from BigDecimal for JSON compatibility
        /** When the bill period started */
        val startTime: String, // ISO string format for JSON compatibility
        /** When the bill was completed */
        val endTime: String, // ISO string format for JSON compatibility
        val deletedBillId: String? = null,
        /** Reason for deletion if bill was deleted */
        val deletingReason: String? = null,
        /** Voucher redeem ID if used */
        val voucherRedeem: List<VoucherDto.RedeemVoucherResponse>? = null,
        /** TSE transaction ID if signed */
        val tseTransactionId: Long? = null,
        /** Generate QR Code from tse signature*/
        val tseQrCodeContent: String? = null,
        /**
         * The TSE values every printed Beleg shows as readable text (AEAO Nr. 2.4.4) — the QR is
         * no longer printed. Null under exactly the conditions [tseQrCodeContent] is null.
         */
        val tseReceiptData: TseReceiptDataDto? = null,
        /**
         * Nachsignierung record when the bill could not be signed immediately (pending, signed late
         * or abandoned) — filled on single-bill reads and the print path only, null on lists and
         * for a bill signed on time.
         */
        val tseSigningNote: TseSigningNoteDto? = null,
        /** VAT details for this bill */
        val vatDetails: List<BillVatDetailDto> = emptyList(),
        /** Order items on this bill */
        val orderItems: List<OrderItemDto.OrderItemResponseDto>, // List of item IDs
        /** Payments for this bill */
        val payments: List<PaymentDto.PaymentResponse>, // List of payment IDs
        /**
         * The server allows a Zahlartwechsel (Storno + Neubuchung) of this bill: a standing sale
         * from an open day that is not a gift-voucher sale or a receivable clearing.
         */
        val isRebookable: Boolean = false,
        /** Set when this bill is a Storno Beleg whose reason is machine-relevant (e.g. a Zahlartwechsel). */
        val stornoReason: StornoReason? = null,
        /**
         * True when this bill may still be storno'd (day open, not already storno'd). Mirrors the
         * server guard in `BillServiceImpl.deleteBill`.
         */
        val isDeletable: Boolean = false,
        /**
         * Tz.-2.7.2 relief precondition: the first Bestellung's StartTransaction log time
         * (Unix seconds). Printed READABLY on the Beleg (not only inside the QR) so the
         * payment-time Kassenbeleg discloses when ordering began (round-3 TSER-3).
         */
        val firstOrderTseStartTime: Long? = null,
        /**
         * The **live** outgoing invoice for this bill — null when none exists, or when the only one
         * was cancelled and the bill's slot is free again.
         *
         * The till disables *Rechnung erstellen* on this rather than discovering the server's 409,
         * and offers a reprint instead. The server re-checks it in `BillInvoiceabilityGuard`; this
         * field is the UI half of that guard, never the enforcement.
         */
        val salesInvoiceId: Long? = null,
        /** The §14 UStG number of [salesInvoiceId]; null while that invoice is still a draft. */
        val salesInvoiceNumber: String? = null,
        /** Open receivable on [salesInvoiceId] as a decimal string; drives the till's settle action. */
        val salesInvoiceOpenAmount: String? = null,
        /** How this bill relates to the others in its family (Storno, original, clearing). Display only. */
        val relations: BillRelationsDto = BillRelationsDto(),
        /**
         * A Schulungsbeleg (AVTraining): booked by an employee in training mode. Not a sale — every
         * printed or PDF copy carries a TRAININGSBELEG marking, and receipt lists badge it. Revenue
         * figures exclude it; the flag is display only, never a guard.
         */
        val isTraining: Boolean = false,
    ) {
        companion object
    }

    /**
     * A pointer to another bill — enough to label a link ("RE-…") and to fetch it by id.
     */
    @Serializable
    data class BillLinkDto(
        val billId: Long,
        val billNumber: String,
    )

    /**
     * The visible links between related bills, derived server-side from the bill's foreign keys
     * (`deleted_bill_id`, `deleting_reason`, `rebooks_bill_id`, `clears_sales_invoice_id`) and its
     * items' `duplicated_from_bill_id`.
     *
     * Display only — no guard reads it; `isDeletable` / `isRebookable` remain the gates.
     * Every field defaults, so an older server (or an older client) simply sees an ordinary sale.
     */
    @Serializable
    data class BillRelationsDto(
        /** What this bill is: sale, Neubuchung, Storno or receivable clearing. */
        val kind: BillKind = BillKind.SALE,
        /** True on a cancelled ORIGINAL (a Storno exists for it). A Storno bill itself is never "cancelled". */
        val isCancelled: Boolean = false,
        /** On a Storno: the sale it reverses. */
        val reversesBill: BillLinkDto? = null,
        /** On a cancelled sale: its Storno. */
        val reversedBy: BillLinkDto? = null,
        /** On a Neubuchung: the stornoed original it replaces. */
        val rebooksBill: BillLinkDto? = null,
        /** On a sale replaced by a Zahlartwechsel: the Neubuchung. */
        val rebookedBy: BillLinkDto? = null,
        /** On a receivable clearing: the sale whose invoice it settles. */
        val clearsBill: BillLinkDto? = null,
        /** On a Storno: why it was booked, when that matters to the reader (a Zahlartwechsel refunded nothing). */
        val stornoReason: StornoReason? = null,
        /**
         * On a sale that settles items a "re-open the table" Storno handed back: the stornoed
         * originals those items came from (`order_item.duplicated_from_bill_id`), distinct, oldest
         * first. Derived from the bill's own items, never stored. Excludes [rebooksBill], which a
         * Zahlartwechsel Neubuchung already names.
         */
        val rebilledFromBills: List<BillLinkDto> = emptyList(),
        /**
         * True when [rebilledFromBills] is non-empty AND the bill also holds items that did not come
         * from those originals (new orders added after the table was re-opened) — "(teilweise)".
         */
        val rebillIsPartial: Boolean = false,
    )

    /**
     * Zahlartwechsel: storno this bill and book it again with [payments], [voucherRedeem] (voucher
     * legs, exactly as in billing) and [tips] (the new bill's whole tip).
     */
    @Serializable
    data class RebookRequestDto(
        val payments: List<PaymentDto.PaymentRequest>,
        val voucherRedeem: List<VoucherDto.RedeemVoucherRequestDto> = emptyList(),
        val tips: String = "0.00",
    )

    /**
     * Request DTO for creating new bills.
     *
     * Used for API requests when creating new bills.
     * Contains required fields for bill setup.
     */
    @Serializable
    data class CreateBillRequestDto(
        /** Table session ID where the bill was generated */
        val tableSessionId: Long,
        val orderItems: List<Long> = emptyList(),
        /** Total bill amount */
        val billAmount: String,
        /** Tip amount (default 0.0) */
        val tips: String = "0.00",
        val payments: List<PaymentDto.PaymentRequest>,
        val voucherRedeem: List<VoucherDto.RedeemVoucherRequestDto> = emptyList(),
        val promotions: List<PromotionDto.PromotionApplyRequestDto> = emptyList(),
    ) {
        /**
         * Validates the create request data.
         *
         * Ensures all required fields are present and values
         * meet business requirements before processing.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(tableSessionId > 0) { "Table session ID must be positive" }
            require(billAmount.isNotBlank()) { "Total amount cannot be negative" }
        }
    }

    /**
     * Summary DTO for bill list responses.
     *
     * Contains essential bill information for list views
     * without full detail to optimize response size.
     */
    @Serializable
    data class BillSummaryDto(
        /** Bill unique identifier */
        val billId: Long,
        /** Unique bill identifier for business operations */
        val billNumber: String,
        /** Total bill amount */
        val billAmount: String,
        val paidAmount: String,
        val paymentType: List<PaymentDto.PaymentSummary> = emptyList(),
        /** Tip amount */
        val tips: String,
        /** When the bill was completed */
        val endTime: String,
        /** Employee ID who created the bill */
        val employeeNumber: String,
        /** Table number from the bill's table session */
        val tableNumber: String? = null,
        /** Table type name for virtual tables (e.g. Mitnahme, Gutschein) */
        val tableType: String? = null,
        /** English table type name for virtual tables */
        val tableTypeEN: String? = null,
        /** Chinese table type name for virtual tables */
        val tableTypeCH: String? = null,
        /** How this bill relates to the others in its family (Storno, original, clearing). Display only. */
        val relations: BillRelationsDto = BillRelationsDto(),
        /** A Schulungsbeleg (AVTraining). Lists that include it badge the row "Training". Display only. */
        val isTraining: Boolean = false,
    )

    /**
     * VAT detail DTO for bill VAT breakdown.
     *
     * Represents VAT calculation details for a specific VAT rate on a bill.
     */
    @Serializable
    data class BillVatDetailDto(
        /** VAT detail unique identifier */
        val id: Long,
        /** VAT ID reference */
        val vatId: String,
        /** VAT rate percentage */
        val vatRate: Double,
        /** VAT amount calculated */
        val vatAmount: String,
        /** Net amount before VAT */
        val netAmount: String,
        /** Total amount including VAT */
        val totalAmount: String,
    ) {
        companion object
    }

    /**
     * Data class for bill statistics aggregation.
     *
     * Contains calculated statistics for bills within a specific date range.
     */
    @Serializable
    data class BillStatisticsDto(
        /** Total number of bills */
        val totalBills: Long,
        /** Total revenue amount */
        val totalRevenue: String,
        /** Total tips amount */
        val totalTips: String,
        /** Average bill amount */
        val averageBillAmount: String,
        /** Number of deleted bills */
        val deletedBills: Long,
        /** Number of bills with vouchers */
        val billsWithVouchers: Long,
        /** Bills per employee statistics */
        val billsByEmployee: Map<String, Long>,
        /** Bills by tip recipient statistics */
        val billsByTipRecipient: Map<String, Long>,
    )

    /**
     * DTO containing TSE data required for DSFinV-K QR code generation.
     *
     * Contains all fields needed to generate a DSFinV-K compliant QR code
     * for German fiscal receipts as required by KassenSichV.
     *
     * QR Code Format (fields separated by semicolon):
     * ```
     * V0;<kassen-seriennummer>;<processType>;<processData>;
     * <transaktions-nummer>;<signatur-zaehler>;<start-zeit>;<log-time>;
     * <sig-alg>;<log-time-format>;<signatur>;<public-key>
     * ```
     *
     * @property kassenSeriennummer Cash register serial number (TSE Client-ID)
     * @property processType TSE process type (e.g., "Kassenbeleg-V1")
     * @property processData The signed process data in DSFinV-K format
     * @property transactionNumber TSE transaction number
     * @property signatureCounter Signature counter from FinishTransaction
     * @property startTimeSeconds Unix timestamp (seconds) from StartTransaction (or first order for Tz. 2.7)
     * @property logTimeSeconds Unix timestamp (seconds) from FinishTransaction
     * @property signatureAlgorithm Signature algorithm (e.g., "ecdsa-plain-SHA384")
     * @property signature Base64-encoded signature from FinishTransaction
     * @property publicKey Base64-encoded TSE public key
     * @property tseSerialNumber TSE hardware serial number
     */
    @Serializable
    data class TseQrCodeDataDto(
        val kassenSeriennummer: String,
        val processType: String,
        val processData: String,
        val transactionNumber: Long,
        val signatureCounter: Long,
        val startTimeSeconds: Long,
        val logTimeSeconds: Long,
        val signatureAlgorithm: String,
        val signature: String,
        val publicKey: String,
        val tseSerialNumber: String,
    )
}
