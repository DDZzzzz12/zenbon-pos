package org.example.project.core.utils

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.money.Decimal

/*
 * Whether part of a bill's tip was paid "auf Rechnung". The Ausgangsrechnung covers the meal only
 * (never the tip), so a receivable tender above the meal total is money the guest owes that no invoice
 * can name. The till hides *Rechnung erstellen* on this answer and says why; the server refuses the
 * same bill with `SALES_INVOICE_TIP_ON_ACCOUNT` (`BillInvoiceabilityGuard`) — CLAUDE.md rule 8.
 */

/** Σ the bill's COMPLETED payments tendered on account (`createsReceivable`). Mirrors the server's `receivableTenderOf`. */
fun BillDto.BillResponseDto.receivableTender(): Decimal =
    payments
        .filter { it.paymentStatus == PaymentStatus.COMPLETED && it.createsReceivable }
        .fold(Decimal.ZERO) { acc, payment -> acc + (Decimal.parseOrNull(payment.amount) ?: Decimal.ZERO) }

/** True when the receivable tender exceeds the meal ([rebookBillTotal]: bill amount minus promotions). */
fun BillDto.BillResponseDto.isTipOnAccount(): Boolean = receivableTender() > rebookBillTotal()
