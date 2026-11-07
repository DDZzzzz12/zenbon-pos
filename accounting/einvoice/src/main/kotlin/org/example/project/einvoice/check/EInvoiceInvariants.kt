package org.example.project.einvoice.check

import org.example.project.einvoice.model.EInvoice
import java.math.BigDecimal

/**
 * Arithmetic consistency checks over a parsed invoice.
 *
 * This is deliberately not EN 16931 rule validation (no `BR-*`, no Schematron). It catches the two
 * classes of problem that would otherwise reach the books silently:
 * - a VAT breakdown that does not add up to the document's own declared tax total (BT-110), and
 * - a tax-exclusive-plus-tax sum that does not match the document's declared tax-inclusive total
 *   (BT-112).
 *
 * Both checks only fire when the invoice actually declares the total in question — some profiles
 * legitimately omit BT-110/BT-112, and an absent total is not a mismatch.
 *
 * Comparison is by value, never by `equals`, because `19.00` and `19.0000` are the same amount and
 * differ only in the scale the sender chose to write.
 */
object EInvoiceInvariants {
    /** @return a human-readable description of the first violation found, or null when consistent. */
    fun check(invoice: EInvoice): String? = vatBreakdownMismatch(invoice) ?: taxInclusiveTotalMismatch(invoice)

    /**
     * BT-110 must equal the sum of the VAT breakdown's per-category amounts (BR-CO-14). A mismatch
     * here means the document's own header total disagrees with the categories it declares.
     */
    private fun vatBreakdownMismatch(invoice: EInvoice): String? {
        val declared = invoice.taxTotal ?: return null
        if (invoice.vatBreakdown.isEmpty()) return null

        val summed = invoice.vatBreakdown.fold(BigDecimal.ZERO) { acc, group -> acc + group.vatAmount }
        if (summed.compareTo(declared) != 0) {
            return "VAT breakdown sums to $summed but the document declares a tax total of $declared"
        }
        return null
    }

    /**
     * BT-112 must equal tax-exclusive plus tax (BR-CO-15). The VAT breakdown's per-category taxable
     * amounts already have any document-level allowances/charges apportioned into them (BR-CO-14),
     * so `sum(taxableAmount) + sum(vatAmount)` is the tax-inclusive total independent of whether the
     * invoice carries allowances or charges — both real corpus fixtures confirm this: the CII
     * fixture's `314.86 + 22.04 = 336.90` matches its declared `336.9` by value, not by scale.
     */
    private fun taxInclusiveTotalMismatch(invoice: EInvoice): String? {
        val declared = invoice.taxInclusiveTotal ?: return null
        if (invoice.vatBreakdown.isEmpty()) return null

        val summed =
            invoice.vatBreakdown.fold(BigDecimal.ZERO) { acc, group -> acc + group.taxableAmount + group.vatAmount }
        if (summed.compareTo(declared) != 0) {
            return "Tax-exclusive plus tax sums to $summed but the document declares a tax-inclusive total of $declared"
        }
        return null
    }
}
