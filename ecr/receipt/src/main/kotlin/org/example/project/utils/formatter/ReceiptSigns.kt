package org.example.project.utils.formatter

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.StornoReason

/**
 * The signed amounts a printed Beleg shows, shared by the ESC/POS and the PDF receipt so both add
 * up to the Beleg's own QR (`…^<paid>:<Bar|Unbar>`).
 *
 * A Storno's DTO carries its OWN totals negated (`billAmount`, `paidAmount`, `tips`,
 * `promotionAmount`, `discountAmount`), but its `payments` are the original's payments, now
 * REFUNDED, with their positive amounts, and its `promotions` are empty — the `bill_promotion` rows
 * stay on the original. Printed as they stand, a Storno read "SUMME -13.50 / BAR 11.50 /
 * GESAMT -11.50" with no Aktion line, against a QR of `-11.50:Bar` (emu10 FAIL 4).
 *
 * Its `orderItems` are the original's items with their positive prices too, so a Storno printed
 * "1x Wein 10.00" above "SUMME -12.50" (emu11 NOTE-3); item lines are negated like the tender.
 *
 * Whether a Beleg is a Storno is read from the bill itself (`relations.kind`, or `deletedBillId`
 * for a DTO built without relations), never guessed from a sign.
 */
object ReceiptSigns {
    /** A Storno Beleg: it pays the original's tender back. */
    fun isStorno(bill: BillDto.BillResponseDto): Boolean = bill.relations.kind == BillKind.STORNO || bill.deletedBillId != null

    /** A payment line's printed amount: on a Storno the refund is paid out, so it prints negative. */
    fun paymentAmount(
        bill: BillDto.BillResponseDto,
        payment: PaymentDto.PaymentResponse,
    ): String = if (isStorno(bill)) formatDecimal(toBigDecimal(payment.amount).negate()) else payment.amount

    /** An item line's printed amount: a Storno takes the original's items back, so they print negative. */
    fun itemAmount(
        bill: BillDto.BillResponseDto,
        stored: String,
    ): String = if (isStorno(bill)) formatDecimal(toBigDecimal(stored).negate()) else stored

    /**
     * The heading a Storno Beleg prints above its items (German, as every receipt line): `STORNO`,
     * or `STORNO (Zahlartwechsel)` for the Storno half of a Zahlartwechsel, then `zu Beleg RE-…`
     * naming the Beleg it reverses — from the relation link, or `deletedBillId` (the original's
     * number) on a DTO built without relations. Empty for any other bill.
     */
    fun stornoHeading(bill: BillDto.BillResponseDto): List<String> {
        if (!isStorno(bill)) return emptyList()
        val reason = bill.relations.stornoReason ?: bill.stornoReason
        val title = if (reason == StornoReason.ZAHLARTWECHSEL) "STORNO (Zahlartwechsel)" else "STORNO"
        val original = bill.relations.reversesBill?.billNumber ?: bill.deletedBillId
        return listOfNotNull(title, original?.let { "zu Beleg $it" })
    }

    /**
     * The marking a Schulungsbeleg (AVTraining) prints at its head AND foot, on paper and PDF alike:
     * a title line and a notice line. Empty for any other bill.
     *
     * Before emu20 FAIL-C a training Beleg printed exactly like a sale — only the QR's process data
     * said `AVTraining`, which nobody reads off the paper. A training receipt handed out (or kept in
     * the drawer) must not be mistakable for a real one. A Storno or reprint of a training bill is a
     * training bill too (the Storno copies `is_training`). ASCII only: the ESC/POS code page has no
     * en dash. The TSE payload is untouched; this is the readable half of the same fact.
     */
    fun trainingMarking(bill: BillDto.BillResponseDto): List<String> = if (bill.isTraining) listOf(TRAINING_TITLE, TRAINING_NOTICE) else emptyList()

    const val TRAINING_TITLE = "TRAININGSBELEG"
    const val TRAINING_NOTICE = "*** KEIN VERKAUF - SCHULUNGSBELEG ***"

    /**
     * A stored reduction (Rabatt, Aktion) as it acts on the printed total: subtracted on a sale
     * (`2.00` → `-2.00`), added back on a Storno, whose reduction is stored negated (`-2.00` → `2.00`).
     */
    fun reduction(stored: String): String = formatDecimal(toBigDecimal(stored).negate())

    /** True when [stored] is a non-zero amount. */
    fun isNonZero(stored: String): Boolean = toBigDecimal(stored).signum() != 0

    /**
     * The promotion lines as (promotion name or null, printed amount). A sale lists its applied
     * promotions by name. A Storno has no rows of its own, so it prints one line for its stored
     * (negated) promotion total — without it the paper's SUMME and GESAMT do not add up.
     */
    fun promotionLines(bill: BillDto.BillResponseDto): List<Pair<String?, String>> =
        when {
            bill.promotions.isNotEmpty() -> bill.promotions.map { it.promotionName to reduction(it.deductedAmount) }
            isStorno(bill) && isNonZero(bill.promotionAmount) -> listOf(null to reduction(bill.promotionAmount))
            else -> emptyList()
        }
}
