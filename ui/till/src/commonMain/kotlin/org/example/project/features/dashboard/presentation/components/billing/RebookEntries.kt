package org.example.project.features.dashboard.presentation.components.billing

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.RebookTender
import org.example.project.core.utils.getLocalizedName
import org.example.project.core.utils.rebookBillTotal
import org.example.project.core.utils.rebookTender
import org.example.project.core.utils.toUserInputDecimalOrNull
import org.example.project.features.dashboard.presentation.state.PaymentEntry
import org.example.project.money.Decimal

/**
 * The [RebookTender] rule the server refuses on (rule 8), shared by the Zahlartwechsel dialog's
 * confirm button and `BillReceiptViewModel.rebookBill`: [entries] and [tip] are well formed, cover
 * exactly [rebookBillTotal] plus the tip and differ from the bill's standing [rebookTender]. A tip
 * below the bill's own is accepted only when [mayLowerTip] — the session holds `BILL_VOID`.
 */
fun BillDto.BillResponseDto.acceptsRebook(
    entries: List<PaymentEntry>,
    tip: Decimal,
    mayLowerTip: Boolean,
): Boolean {
    val tender = RebookTender(entries.map { it.paymentTypeId to it.amount }, tip, entries.mapNotNull { it.voucherNumber })
    val original = rebookTender()
    return tender.isWellFormed() &&
        tender.balances(rebookBillTotal()) &&
        tender.changesFrom(original) &&
        (mayLowerTip || !tender.lowersTipFrom(original))
}

/** [tip] is below the bill's own and [mayLowerTip] is false: the dialog says why it cannot confirm. */
fun BillDto.BillResponseDto.rebookTipDecreaseRefused(
    tip: Decimal,
    mayLowerTip: Boolean,
): Boolean = !mayLowerTip && RebookTender(payments = emptyList(), tip = tip).lowersTipFrom(rebookTender())

/**
 * The bill's standing tender as the Zahlartwechsel dialog's starting entries: one per COMPLETED
 * payment, a voucher leg carrying the number and source of the redemption that settled it. A kept
 * voucher leg is therefore sent as it stands and never re-validated — validation would read the
 * voucher's balance with this very redemption still deducted. Each entry is named in [language]
 * (the translation, else the German name), as the bill's own payment rows above it are.
 */
fun BillDto.BillResponseDto.rebookEntries(language: Language = Language.GERMAN): List<PaymentEntry> {
    val redemptionByPayment = voucherRedeem.orEmpty().filter { it.paymentId != null }.associateBy { it.paymentId }
    return payments
        .filter { it.paymentStatus == PaymentStatus.COMPLETED }
        .map { payment ->
            val redemption = redemptionByPayment[payment.id]
            PaymentEntry(
                id = "orig-${payment.id}",
                paymentTypeId = payment.paymentTypeId,
                paymentTypeName = payment.getLocalizedName(language),
                amount = Decimal.parseOrNull(payment.amount) ?: Decimal.ZERO,
                voucherNumber = redemption?.voucherNumber,
                voucherSource = redemption?.voucherSource,
            )
        }
}

/**
 * The amount the next payment of a Zahlartwechsel carries, as the billing screen's numpad decides it:
 * a blank field takes the whole [remaining] amount, a typed positive amount is taken as typed (the
 * confirm button still requires the tender to balance). Null when nothing can be added — the field
 * does not parse, is not positive, or a blank field meets nothing left to pay.
 */
fun rebookNextPaymentAmount(
    typed: String,
    remaining: Decimal,
): Decimal? {
    if (typed.isBlank()) return remaining.takeIf { it > Decimal.ZERO }
    return typed.toUserInputDecimalOrNull()?.takeIf { it > Decimal.ZERO }
}
