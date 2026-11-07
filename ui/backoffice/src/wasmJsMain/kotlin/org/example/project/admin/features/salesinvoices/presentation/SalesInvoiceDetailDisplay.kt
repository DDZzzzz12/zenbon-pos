package org.example.project.admin.features.salesinvoices.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.presentation.strings.StringKey
import org.example.project.money.Decimal
import org.example.project.money.MoneyException
import org.example.project.money.MoneyRounding

/**
 * The localized label for an invoice's wire status (`DRAFT` / `ISSUED` / `CANCELLED`), or null for
 * a value this client does not know — the caller then shows the raw value rather than nothing, so a
 * newer server's status is still readable.
 */
internal fun salesInvoiceStatusLabelKey(status: String): StringKey? =
    when (SalesInvoiceStatus.entries.firstOrNull { it.name == status }) {
        SalesInvoiceStatus.DRAFT -> AdminStringKeys.SalesInvoicesStatusDraft
        SalesInvoiceStatus.ISSUED -> AdminStringKeys.SalesInvoicesStatusIssued
        SalesInvoiceStatus.CANCELLED -> AdminStringKeys.SalesInvoicesStatusCancelled
        null -> null
    }

/**
 * A line quantity for display: `2.000` → `2`, `1.5` → `1,5` (German decimal comma, at most three
 * decimals). Unparseable input comes back unchanged — a display must never crash a screen.
 */
internal fun formatInvoiceQuantity(quantity: String): String {
    val value = Decimal.parseOrNull(quantity) ?: return quantity
    val plain =
        try {
            value.setScale(QUANTITY_DISPLAY_SCALE, MoneyRounding.HALF_UP).toPlainString()
        } catch (e: MoneyException) {
            return quantity
        }
    val trimmed = if ('.' in plain) plain.trimEnd('0').trimEnd('.') else plain
    return trimmed.replace('.', ',')
}

/**
 * The gross of one VAT-breakdown row (BG-23): taxable amount + VAT, summed in [Decimal]. The wire
 * carries only the two parts; the PDF prints all three, so the panel derives the third exactly.
 * Returns the taxable amount unchanged when either part is unparseable.
 */
internal fun SalesInvoiceDto.VatRowDto.grossAmount(): String {
    val taxable = Decimal.parseOrNull(taxableAmount) ?: return taxableAmount
    val vat = Decimal.parseOrNull(vatAmount) ?: return taxableAmount
    return (taxable + vat).toPlainString()
}

/**
 * The invoice's net total as the PDF prints it: BT-109 (after document-level allowances) when the
 * server sent it, else BT-106 (the line nets) — an invoice without allowances has the two equal.
 */
internal fun SalesInvoiceDto.ResponseDto.displayNetTotal(): String? = taxExclusiveTotal ?: lineNetTotal

private const val QUANTITY_DISPLAY_SCALE = 3
