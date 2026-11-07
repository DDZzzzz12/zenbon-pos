package org.example.project.core.utils

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.money.Decimal

/*
 * A bill on screen, read as the inputs of a Zahlartwechsel (Storno + Neubuchung). The till checks a
 * new tender against these with [RebookTender] before it offers the confirm button; the server
 * checks the same rule on its own copy of the bill (`BillServiceImpl.currentTender`).
 */

/** What a Zahlartwechsel must bill: the bill amount minus its promotions (the tip comes on top). */
fun BillDto.BillResponseDto.rebookBillTotal(): Decimal = billAmount.moneyOrZero() - promotionAmount.moneyOrZero()

/**
 * The bill's standing tender — its COMPLETED payments, its tip and its redeemed voucher numbers —
 * which a Zahlartwechsel must differ from. Mirrors the server's `Bill.currentTender()`.
 *
 * The voucher numbers come from [BillDto.BillResponseDto.voucherRedeem], which does not say which
 * payment a redemption belongs to; on a bill that can still be rebooked no payment is refunded, so
 * every listed redemption is a standing one.
 */
fun BillDto.BillResponseDto.rebookTender(): RebookTender =
    RebookTender(
        payments =
            payments
                .filter { it.paymentStatus == PaymentStatus.COMPLETED }
                .map { it.paymentTypeId to it.amount.moneyOrZero() },
        tip = tips.moneyOrZero(),
        vouchers = voucherRedeem.orEmpty().map { it.voucherNumber },
    )

private fun String.moneyOrZero(): Decimal = Decimal.parseOrNull(this) ?: Decimal.ZERO
