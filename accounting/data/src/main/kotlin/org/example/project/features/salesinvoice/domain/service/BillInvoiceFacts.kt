package org.example.project.features.salesinvoice.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.bill.BillKind
import java.math.BigDecimal

/**
 * Everything the mapper needs from a bill, read once so the mapper stays pure.
 *
 * [vatRows] is the **fiscal split the receipt printed** (`bill_vat_detail`), which the invoice copies
 * rather than recomputes. [lines] are descriptive: `t_order_item` carries no per-line VAT, so the
 * mapper derives it.
 */
data class BillInvoiceFacts(
    val billId: Long,
    val billNumber: String,
    val billAmount: BigDecimal,
    val paidAt: LocalDateTime,
    /** What the bill is in its family (sale, correction, Storno, clearing), from its own foreign keys. */
    val kind: BillKind,
    /** True on a cancelled ORIGINAL — a Storno exists for it. */
    val isCancelled: Boolean,
    val isTraining: Boolean,
    val hasLiveInvoice: Boolean,
    val lines: List<BillInvoiceLineFact>,
    val vatRows: List<BillInvoiceVatFact>,
    val tse: DraftTseBlock?,
    val seller: InvoiceSeller,
    /**
     * The sum of the bill's effective payment legs tendered "auf Rechnung" — the part still owed.
     * Zero for a bill settled in full at the till. Drives the payment note: only the paid part may
     * be acknowledged as received.
     */
    val receivableTender: BigDecimal = BigDecimal.ZERO,
    /**
     * The bill's OWNER tip (`TrinkgeldAG`), zero for an employee tip or none. The bill folded it into
     * [vatRows] as taxable revenue; the invoice leaves it out ([OwnerTipRemoval]). An EMPLOYEE tip is
     * not in [vatRows] at all, so it needs no removal and is not carried here.
     */
    val ownerTip: BigDecimal,
    /**
     * `bill.promotion_amount` — Σ of every promotion the bill deducted. [billAmount] is the items
     * BEFORE promotions; the VAT rows are after them.
     */
    val promotionAmount: BigDecimal,
    /**
     * The bill's stored `bill_promotion` rows, in the order they were applied. Each becomes an EN 16931
     * document-level allowance (BG-20) on the invoice ([BillInvoiceReconciliation]).
     */
    val promotions: List<BillInvoicePromotionFact>,
)
