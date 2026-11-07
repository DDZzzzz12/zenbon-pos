package org.example.project.features.matching.data.service

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * One invoice a payment might settle, reduced to the plain values matching needs.
 *
 * Every field mirrors a nullable column on `invoice`, so it is nullable here too — a hand-captured
 * invoice may carry no number, no supplier and no date, and a matcher that assumed otherwise would
 * either crash or invent an agreement.
 *
 * [openAmount] is what is still unsettled, not the invoice total: a partly paid invoice is matched
 * on its residual. `BigDecimal`, compared with `compareTo` — never `equals` (scale-sensitive) and
 * never a `Double`.
 *
 * There is deliberately **no `shopId`**. The cross-shop guard of spec §7 cannot be written on this
 * branch: `invoice.shop_id` was specced in §3.4 but never added, and the system is single-shop. It
 * lives instead as `sameShop` on [isEligible], so the guard is recorded rather than silently
 * dropped.
 */
data class InvoiceCandidate(
    val id: Long,
    val invoiceNumber: String? = null,
    val paymentReference: String? = null,
    val supplierId: Long? = null,
    val issueDate: LocalDate? = null,
    val openAmount: BigDecimal? = null,
    /**
     * BT-9, when the invoice states one. Signal 6 needs it and suggests nothing without it: an
     * invoice that cannot be shown to fall inside the window is an unprovable match.
     */
    val dueDate: LocalDate? = null,
)
