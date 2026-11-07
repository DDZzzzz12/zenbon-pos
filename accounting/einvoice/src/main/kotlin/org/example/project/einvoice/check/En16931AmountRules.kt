package org.example.project.einvoice.check

import org.example.project.einvoice.model.EInvoice
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The EN 16931 amount rules an invoice **we write** must satisfy before it is issued.
 *
 * Deliberately separate from [EInvoiceInvariants], which also runs on invoices we *receive*: a
 * supplier's four-decimal amounts are their validator's problem, not a reason to refuse booking
 * their invoice. Ours are our problem, and a validator at the customer's end rejects them.
 *
 * - **BR-DEC-01 … 20, 23:** document totals (BT-106 … 115), the allowance and charge amounts (BT-92,
 *   BT-93, BT-99, BT-100), the VAT breakdown (BT-116, 117) and the line net amount (BT-131) have at
 *   most two decimals. The unit price (BT-146) is exempt and routinely carries four.
 * - **BR-CO-10:** the line net amounts sum to BT-106.
 * - **BR-CO-11 / BR-CO-12:** the document-level allowances sum to BT-107, the charges to BT-108 (an
 *   absent total reads as zero).
 * - **BR-CO-13:** BT-109 = BT-106 − BT-107 + BT-108.
 * - **BR-S-08 and siblings:** each VAT group's taxable amount equals the net of its lines, less the
 *   document-level allowances and plus the charges of that category and rate.
 * - **BR-CO-17:** each VAT group's VAT (BT-117) is its taxable amount (BT-116) × rate (BT-119) / 100,
 *   rounded to two decimals — HALF_UP, symmetric about zero, so a credit note's negated group passes
 *   exactly when the positive one does.
 * - **BR-CO-16:** amount due (BT-115) = BT-112 − BT-113 + BT-114, absent terms reading as zero.
 *
 * Comparison is by value, never `equals`: `19.00` and `19.0000` are one amount.
 */
object En16931AmountRules {
    /** @return the first violation, or null when every rule holds. */
    fun check(invoice: EInvoice): String? =
        decimalProblem(invoice)
            ?: lineSumProblem(invoice)
            ?: allowanceSumProblem(invoice)
            ?: taxExclusiveProblem(invoice)
            ?: groupSumProblem(invoice)
            ?: groupVatProblem(invoice)
            ?: amountDueProblem(invoice)

    private fun decimalProblem(invoice: EInvoice): String? {
        val amounts =
            buildList {
                add("BT-106 line total" to invoice.lineNetTotal)
                add("BT-107 allowance total" to invoice.allowanceTotal)
                add("BT-108 charge total" to invoice.chargeTotal)
                add("BT-109 tax-exclusive total" to invoice.taxExclusiveTotal)
                add("BT-110 tax total" to invoice.taxTotal)
                add("BT-112 tax-inclusive total" to invoice.taxInclusiveTotal)
                add("BT-113 prepaid amount" to invoice.prepaidAmount)
                add("BT-114 rounding amount" to invoice.roundingAmount)
                add("BT-115 amount due" to invoice.amountDue)
                invoice.vatBreakdown.forEach {
                    add("BT-116 taxable amount (${it.categoryCode} ${it.rate.toPlainString()} %)" to it.taxableAmount)
                    add("BT-117 VAT amount (${it.categoryCode} ${it.rate.toPlainString()} %)" to it.vatAmount)
                }
                invoice.lines.forEach { add("BT-131 net amount of line ${it.lineId}" to it.lineNetAmount) }
                invoice.allowancesCharges.forEachIndexed { index, it ->
                    val (amountTerm, baseTerm) = if (it.isCharge) "BT-99" to "BT-100" else "BT-92" to "BT-93"
                    add("$amountTerm amount of allowance/charge ${index + 1}" to it.amount)
                    add("$baseTerm base amount of allowance/charge ${index + 1}" to it.baseAmount)
                }
            }
        val (term, value) = amounts.firstOrNull { (_, value) -> value != null && value.hasSubCent() } ?: return null
        return "$term has more than two decimals (${value?.toPlainString()}); EN 16931 BR-DEC allows two"
    }

    private fun lineSumProblem(invoice: EInvoice): String? {
        val declared = invoice.lineNetTotal ?: return null
        if (invoice.lines.isEmpty()) return null
        val summed = invoice.lines.fold(BigDecimal.ZERO) { acc, line -> acc + line.lineNetAmount }
        if (summed.compareTo(declared) != 0) {
            return "invoice lines sum to ${summed.toPlainString()} but the line total (BT-106) is ${declared.toPlainString()} (BR-CO-10)"
        }
        return null
    }

    private fun allowanceSumProblem(invoice: EInvoice): String? {
        val (charges, allowances) = invoice.allowancesCharges.partition { it.isCharge }
        val checks =
            listOf(
                Triple("allowances", allowances, invoice.allowanceTotal) to "BT-107, BR-CO-11",
                Triple("charges", charges, invoice.chargeTotal) to "BT-108, BR-CO-12",
            )
        for ((check, rule) in checks) {
            val (what, items, declared) = check
            val summed = items.fold(BigDecimal.ZERO) { acc, it -> acc + it.amount }
            if (summed.compareTo(declared ?: BigDecimal.ZERO) != 0) {
                return "document-level $what sum to ${summed.toPlainString()} but their total is " +
                    "${(declared ?: BigDecimal.ZERO).toPlainString()} ($rule)"
            }
        }
        return null
    }

    private fun taxExclusiveProblem(invoice: EInvoice): String? {
        val lineNet = invoice.lineNetTotal ?: return null
        val taxExclusive = invoice.taxExclusiveTotal ?: return null
        val expected = lineNet - (invoice.allowanceTotal ?: BigDecimal.ZERO) + (invoice.chargeTotal ?: BigDecimal.ZERO)
        if (expected.compareTo(taxExclusive) != 0) {
            return "tax-exclusive total (BT-109) is ${taxExclusive.toPlainString()} but BT-106 − BT-107 + BT-108 is " +
                "${expected.toPlainString()} (BR-CO-13)"
        }
        return null
    }

    private fun groupSumProblem(invoice: EInvoice): String? {
        if (invoice.lines.isEmpty()) return null
        for (group in invoice.vatBreakdown) {
            val summed =
                invoice.lines
                    .filter { it.vatCategoryCode == group.categoryCode && it.vatRate.compareTo(group.rate) == 0 }
                    .fold(BigDecimal.ZERO) { acc, line -> acc + line.lineNetAmount }
            val adjustment =
                invoice.allowancesCharges
                    .filter { it.vatCategoryCode == group.categoryCode && it.vatRate.compareTo(group.rate) == 0 }
                    .fold(BigDecimal.ZERO) { acc, it -> if (it.isCharge) acc + it.amount else acc - it.amount }
            if ((summed + adjustment).compareTo(group.taxableAmount) != 0) {
                val less = if (adjustment.signum() == 0) "" else " (${(summed + adjustment).toPlainString()} after allowances and charges)"
                return "lines of VAT group ${group.categoryCode} ${group.rate.toPlainString()} % sum to ${summed.toPlainString()}$less " +
                    "but its taxable amount (BT-116) is ${group.taxableAmount.toPlainString()}"
            }
        }
        return null
    }

    private fun groupVatProblem(invoice: EInvoice): String? {
        val group =
            invoice.vatBreakdown.firstOrNull { it.vatAmount.compareTo(vatOf(it.taxableAmount, it.rate)) != 0 } ?: return null
        return "VAT group ${group.categoryCode} ${group.rate.toPlainString()} % states VAT ${group.vatAmount.toPlainString()} " +
            "but ${group.taxableAmount.toPlainString()} × ${group.rate.toPlainString()} % is " +
            "${vatOf(group.taxableAmount, group.rate).toPlainString()} (BR-CO-17)"
    }

    private fun amountDueProblem(invoice: EInvoice): String? {
        val due = invoice.amountDue ?: return null
        val grand = invoice.taxInclusiveTotal ?: return null
        val expected = grand - (invoice.prepaidAmount ?: BigDecimal.ZERO) + (invoice.roundingAmount ?: BigDecimal.ZERO)
        if (due.compareTo(expected) != 0) {
            return "amount due (BT-115) is ${due.toPlainString()} but BT-112 − BT-113 + BT-114 is ${expected.toPlainString()} (BR-CO-16)"
        }
        return null
    }

    /** BR-CO-17's product, rounded HALF_UP to cents. `rate` is in percent units (`19.00` = 19 %). */
    fun vatOf(
        taxable: BigDecimal,
        rate: BigDecimal,
    ): BigDecimal = taxable.multiply(rate).movePointLeft(2).setScale(2, RoundingMode.HALF_UP)

    private fun BigDecimal.hasSubCent(): Boolean = stripTrailingZeros().scale() > 2
}
