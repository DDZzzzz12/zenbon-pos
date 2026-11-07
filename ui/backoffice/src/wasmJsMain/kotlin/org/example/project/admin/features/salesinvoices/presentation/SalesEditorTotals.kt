package org.example.project.admin.features.salesinvoices.presentation

import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.money.Decimal
import org.example.project.money.MoneyRounding

/**
 * The Summen of the editor — Netto, USt per rate, Brutto — derived from the typed gross lines. It is
 * **feedback only**: the server re-derives net and VAT and sums the breakdown it trusts, exactly as for
 * the old Zwischensumme.
 *
 * Per rate the line grosses are summed first and then split, with the VAT taken **by subtraction** so
 * `net + vat == gross` holds exactly (the invariant the server's breakdown keeps). Lines that do not
 * parse are skipped, like [SalesInvoiceEditorState.subtotal].
 */
data class SalesEditorTotals(
    val net: Decimal,
    val vatRows: List<SalesTotalsVatRow>,
    val gross: Decimal,
) {
    companion object {
        private const val MONEY_SCALE = 2
        private const val DIVISOR_SCALE = MONEY_SCALE + 2

        fun of(lines: List<EditorLine>): SalesEditorTotals {
            val grossByRate = linkedMapOf<String, Decimal>()
            lines.forEach { line ->
                val quantity = Decimal.parseOrNull(line.quantity.trim()) ?: return@forEach
                val price = line.grossUnitPrice.toCanonicalMoneyOrNull()?.let(Decimal::parseOrNull) ?: return@forEach
                grossByRate[line.vatRate] = (grossByRate[line.vatRate] ?: Decimal.ZERO) + quantity * price
            }
            var net = Decimal.ZERO
            var gross = Decimal.ZERO
            val rows =
                grossByRate.mapNotNull { (rate, rawGross) ->
                    val parsedRate = Decimal.parseOrNull(rate) ?: return@mapNotNull null
                    val rateGross = rawGross.setScale(MONEY_SCALE, MoneyRounding.HALF_UP)
                    val divisor = Decimal.ONE + parsedRate.divide(Decimal.HUNDRED, DIVISOR_SCALE, MoneyRounding.HALF_UP)
                    val rateNet = rateGross.divide(divisor, MONEY_SCALE, MoneyRounding.HALF_UP)
                    net += rateNet
                    gross += rateGross
                    SalesTotalsVatRow(rate = rate, vat = rateGross - rateNet)
                }
            return SalesEditorTotals(net = net, vatRows = rows, gross = gross)
        }
    }
}
