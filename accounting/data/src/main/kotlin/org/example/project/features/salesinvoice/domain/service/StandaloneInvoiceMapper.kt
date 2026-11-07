package org.example.project.features.salesinvoice.domain.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Derives the content of a case-C invoice from its line inputs.
 *
 * The rule that makes case A exact — *copy the fiscal split* — has no counterpart here: there is no
 * bill to copy. So the opposite rule applies: **everything is derived from the lines**, in the one
 * way that cannot break the `VatSplit` invariant — `net = gross ÷ (1 + rate)` rounded HALF_UP at
 * scale 2, `vat = gross − net` by subtraction — and the BG-23 breakdown is summed per rate.
 *
 * Pure: line inputs in, derived content out. The service calls this inside its own `dbQuery`.
 */
object StandaloneInvoiceMapper {
    private const val NET_SCALE = 2
    private val HUNDRED = BigDecimal("100")

    fun toContent(lines: List<SalesInvoiceDto.StandaloneLineInputDto>): MappedLines {
        if (lines.isEmpty()) {
            throw PosException(
                PosErrorCode.SALES_INVOICE_NOT_ISSUABLE,
                HttpStatusCode.UnprocessableEntity,
                "A standalone invoice needs at least one line",
            )
        }

        val derived =
            lines.map { line ->
                val gross =
                    line.grossUnitPrice.toBigDecimalOrNull()
                        ?: throw PosException(
                            PosErrorCode.SALES_INVOICE_NOT_ISSUABLE,
                            HttpStatusCode.UnprocessableEntity,
                            "Line amount '${line.grossUnitPrice}' is not a number",
                        )
                val quantity =
                    line.quantity.toBigDecimalOrNull()
                        ?: throw PosException(
                            PosErrorCode.SALES_INVOICE_NOT_ISSUABLE,
                            HttpStatusCode.UnprocessableEntity,
                            "Line quantity '${line.quantity}' is not a number",
                        )
                val ratePercent =
                    line.vatRate.toBigDecimalOrNull()
                        ?: throw PosException(
                            PosErrorCode.SALES_INVOICE_NOT_ISSUABLE,
                            HttpStatusCode.UnprocessableEntity,
                            "Line VAT rate '${line.vatRate}' is not a number",
                        )

                // A rate of -100 makes the divisor exactly zero and a smaller one makes it negative:
                // both would produce an arithmetic error or an invented split, not a refusal. The
                // DTO validator rejects a negative rate, but this guard holds even when a caller
                // bypasses it — a validator that only lives in the DTO is one the next caller skips.
                if (BigDecimal.ONE.add(ratePercent.divide(HUNDRED)).signum() <= 0) {
                    throw PosException(
                        PosErrorCode.SALES_INVOICE_NOT_ISSUABLE,
                        HttpStatusCode.UnprocessableEntity,
                        "Line VAT rate '$ratePercent' is not a valid percentage: " +
                            "1 + rate/100 must stay positive",
                    )
                }

                val lineGross = gross.multiply(quantity)
                val net = lineGross.divide(BigDecimal.ONE.add(ratePercent.divide(HUNDRED)), NET_SCALE, RoundingMode.HALF_UP)
                val vat = lineGross.subtract(net)
                StandaloneLineContent(
                    itemName = line.itemName.trim(),
                    itemDescription = line.itemDescription?.trim()?.ifBlank { null },
                    quantity = quantity,
                    unitCode = line.unitCode.ifBlank { "C62" },
                    // Carried verbatim, not re-derived: the editor reads it back on reopen, and
                    // `line_gross / quantity` at scale 2 cannot round-trip a sub-cent unit price.
                    grossUnitPrice = gross,
                    netUnitPrice = net.divide(quantity, 4, RoundingMode.HALF_UP),
                    lineNetAmount = net,
                    vatRate = ratePercent,
                    vatCategoryCode = line.vatCategoryCode ?: vatCategoryFor(ratePercent),
                    vatAmount = vat,
                    lineGrossAmount = lineGross,
                )
            }

        val breakdown =
            derived
                .groupBy { it.vatRate to it.vatCategoryCode }
                .map { (rateAndCategory, linesOfRate) ->
                    StandaloneVatRowContent(
                        vatRatePercent = rateAndCategory.first,
                        vatCategoryCode = rateAndCategory.second,
                        taxableAmount = linesOfRate.fold(BigDecimal.ZERO) { acc, l -> acc + l.lineNetAmount },
                        vatAmount = linesOfRate.fold(BigDecimal.ZERO) { acc, l -> acc + l.vatAmount },
                    )
                }.sortedBy { it.vatRatePercent }

        return MappedLines(
            lines = derived,
            vatRows = breakdown,
            lineNetTotal = breakdown.fold(BigDecimal.ZERO) { acc, row -> acc + row.taxableAmount },
            taxTotal = breakdown.fold(BigDecimal.ZERO) { acc, row -> acc + row.vatAmount },
            taxInclusiveTotal = derived.fold(BigDecimal.ZERO) { acc, l -> acc + l.lineGrossAmount },
        )
    }

    /** `Z` for a zero rate, `S` otherwise — the restaurant default. `AE`/`K` are explicit on the input. */
    private fun vatCategoryFor(ratePercent: BigDecimal): String = if (ratePercent.signum() == 0) "Z" else "S"
}

/** What the mapper returns: the derived lines, the summed breakdown and the derived totals. */
data class MappedLines(
    val lines: List<StandaloneLineContent>,
    val vatRows: List<StandaloneVatRowContent>,
    val lineNetTotal: BigDecimal,
    val taxTotal: BigDecimal,
    val taxInclusiveTotal: BigDecimal,
)
