package org.example.project.admin.features.salesinvoices.presentation

import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.money.Decimal

/**
 * What an allowance (BG-20) does to the invoice total, as a money string for [org.example.project.ui
 * .components.PosMoneyText]: an allowance takes its gross off (negative on an invoice, positive on
 * the Gutschrift that reverses it), a charge adds it. Computed in [Decimal], never a `Double`.
 */
internal fun SalesInvoiceDto.AllowanceChargeDto.effectOnTotal(): String {
    val gross = Decimal.parseOrNull(grossAmount) ?: return grossAmount
    return (if (isCharge) gross else -gross).toPlainString()
}

/** `Aktion: Happy Hour (19 %)` — the reason and the rate it was taken off at. */
internal fun SalesInvoiceDto.AllowanceChargeDto.label(): String {
    val rate = if ('.' in vatRate) vatRate.trimEnd('0').trimEnd('.') else vatRate
    return "${reason.orEmpty()} ($rate %)".trim()
}
