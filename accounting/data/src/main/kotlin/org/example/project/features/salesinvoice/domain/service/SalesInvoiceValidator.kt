package org.example.project.features.salesinvoice.domain.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import java.math.BigDecimal

/**
 * The content rules an outgoing invoice must satisfy **before** a number is consumed.
 *
 * Order matters: this runs first in `issue()`, so a rejected issuance leaves the sequence untouched.
 * Validating after taking a number would turn every content mistake into a permanent gap.
 *
 * Every amount comparison uses `compareTo`, never `equals`: `BigDecimal("37.89")` and
 * `BigDecimal("37.8900")` are the same money and different objects, and `equals` compares scale.
 */
object SalesInvoiceValidator {
    fun requireIssuable(content: SalesInvoiceContent) {
        if (content.buyerName.isBlank()) {
            throw notIssuable("An invoice needs a buyer name (§14 Abs. 4 Nr. 1 UStG)")
        }
        if (!content.hasServiceDate) {
            throw notIssuable("An invoice needs a Leistungszeitpunkt (§14 Abs. 4 Nr. 6 UStG)")
        }
        if (content.lines.isEmpty()) {
            throw notIssuable("An invoice needs at least one line")
        }

        content.lines.forEachIndexed { index, line ->
            val expected = line.netAmount + line.vatAmount
            if (expected.compareTo(line.grossAmount) != 0) {
                throw notIssuable(
                    "Line ${index + 1}: net ${line.netAmount} + VAT ${line.vatAmount} is $expected, " +
                        "but the line gross says ${line.grossAmount}",
                )
            }
        }

        val vatSum = content.vatRows.fold(BigDecimal.ZERO) { acc, row -> acc + row.vatAmount }
        val taxTotal = content.taxTotal ?: throw notIssuable("An invoice needs a tax total")
        if (vatSum.compareTo(taxTotal) != 0) {
            throw notIssuable("The VAT breakdown sums to $vatSum but the tax total says $taxTotal")
        }

        requireAllowancesConsistent(content)

        val lineGrossSum = content.lines.fold(BigDecimal.ZERO) { acc, line -> acc + line.grossAmount }
        val adjustment = content.allowances.fold(BigDecimal.ZERO) { acc, a -> acc + a.signedGross() }
        val inclusiveTotal =
            content.taxInclusiveTotal ?: throw notIssuable("An invoice needs a tax-inclusive total")
        if ((lineGrossSum + adjustment).compareTo(inclusiveTotal) != 0) {
            val less = if (adjustment.signum() == 0) "" else " (${adjustment.toPlainString()} after allowances and charges)"
            throw notIssuable("The lines sum to $lineGrossSum$less but the invoice total says $inclusiveTotal")
        }

        requireTaxExclusiveTotal(content)
        if (content.allowances.isNotEmpty()) requireGroupsAbsorbAllowances(content)
    }

    /**
     * Each allowance or charge (BG-20/21) is a non-negative net amount whose VAT is by subtraction, and
     * their sums are the header's BT-107 and BT-108 (BR-CO-11/12).
     */
    private fun requireAllowancesConsistent(content: SalesInvoiceContent) {
        content.allowances.forEachIndexed { index, allowance ->
            val what = "${if (allowance.isCharge) "Charge" else "Allowance"} ${index + 1}"
            if (allowance.amount.signum() < 0) throw notIssuable("$what: its amount ${allowance.amount} is negative")
            if ((allowance.amount + allowance.vatAmount).compareTo(allowance.grossAmount) != 0) {
                throw notIssuable(
                    "$what: net ${allowance.amount} + VAT ${allowance.vatAmount} is ${allowance.amount + allowance.vatAmount}, " +
                        "but its gross says ${allowance.grossAmount}",
                )
            }
        }
        requireSum("allowances", content.allowances.filterNot { it.isCharge }, content.allowanceTotal, "BT-107")
        requireSum("charges", content.allowances.filter { it.isCharge }, content.chargeTotal, "BT-108")
    }

    private fun requireSum(
        what: String,
        items: List<SalesInvoiceAllowanceContent>,
        declared: BigDecimal?,
        term: String,
    ) {
        val sum = items.fold(BigDecimal.ZERO) { acc, a -> acc + a.amount }
        if (sum.compareTo(declared ?: BigDecimal.ZERO) != 0) {
            throw notIssuable("The $what sum to $sum but the invoice's $term says ${declared ?: BigDecimal.ZERO}")
        }
    }

    /** BR-CO-13: BT-109 = BT-106 − BT-107 + BT-108, when the invoice states both totals. */
    private fun requireTaxExclusiveTotal(content: SalesInvoiceContent) {
        val lineNet = content.lineNetTotal ?: return
        val taxExclusive = content.taxExclusiveTotal ?: return
        val expected = lineNet - (content.allowanceTotal ?: BigDecimal.ZERO) + (content.chargeTotal ?: BigDecimal.ZERO)
        if (expected.compareTo(taxExclusive) != 0) {
            throw notIssuable(
                "The line net total $lineNet less allowances plus charges is $expected, but the tax-exclusive total says $taxExclusive",
            )
        }
    }

    /**
     * Per VAT rate, the breakdown's gross is the lines' gross less that rate's allowances plus its
     * charges: an allowance's VAT is absorbed by the group it names (BR-S-08 and siblings, on the
     * gross side, where the till's amounts are exact).
     */
    private fun requireGroupsAbsorbAllowances(content: SalesInvoiceContent) {
        val rates = (content.lines.map { it.vatRate } + content.vatRows.map { it.vatRate } + content.allowances.map { it.vatRate })
        rates.distinctBy { it.stripTrailingZeros() }.forEach { rate ->
            val lines = content.lines.filter { it.vatRate.compareTo(rate) == 0 }.fold(BigDecimal.ZERO) { acc, l -> acc + l.grossAmount }
            val adjustment =
                content.allowances.filter { it.vatRate.compareTo(rate) == 0 }.fold(BigDecimal.ZERO) { acc, a -> acc + a.signedGross() }
            val group =
                content.vatRows.filter { it.vatRate.compareTo(rate) == 0 }.fold(BigDecimal.ZERO) { acc, r -> acc + r.taxableAmount + r.vatAmount }
            if ((lines + adjustment).compareTo(group) != 0) {
                throw notIssuable(
                    "VAT group ${rate.stripTrailingZeros().toPlainString()} %: the lines ($lines) with their allowances and charges " +
                        "come to ${lines + adjustment}, but the VAT breakdown says $group",
                )
            }
        }
    }

    /** What an allowance (−) or a charge (+) does to the gross. */
    private fun SalesInvoiceAllowanceContent.signedGross(): BigDecimal = if (isCharge) grossAmount else grossAmount.negate()

    private fun notIssuable(message: String) =
        PosException(
            PosErrorCode.SALES_INVOICE_NOT_ISSUABLE,
            HttpStatusCode.UnprocessableEntity,
            message,
        )
}
