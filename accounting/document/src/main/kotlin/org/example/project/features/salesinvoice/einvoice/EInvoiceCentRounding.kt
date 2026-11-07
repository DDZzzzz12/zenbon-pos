package org.example.project.features.salesinvoice.einvoice

import org.example.project.einvoice.check.En16931AmountRules
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceAllowanceCharge
import org.example.project.einvoice.model.EInvoiceLine
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Brings an outgoing [EInvoice] to the two decimals EN 16931 allows for monetary amounts, without
 * breaking the sums that tie those amounts together — and states each VAT group's VAT as
 * `taxable × rate`, which BR-CO-17 demands and the fiscal split does not always give.
 *
 * `sales_invoice` stores money at `DECIMAL(19,4)`, and a from-bill line's net is derived at four
 * decimals (`10.00 / 1.19 = 8.4034`). The BR-DEC rules cap every document-level amount (BT-106 …
 * BT-115), the VAT breakdown (BT-116/117) and the line net amount (BT-131) at two decimals; only the
 * unit price (BT-146) and quantities may carry more. Rounding each amount on its own would satisfy
 * BR-DEC and break the BR-CO sums — two `8.4034` lines round to `16.80`, while the fiscal split the
 * receipt printed says `16.81`.
 *
 * ## BR-CO-17 against the fiscal split — why a cent moves into BT-114
 *
 * The till derives VAT **by subtraction** — `net = round(gross / (1 + q))`, `vat = gross − net` —
 * and that split is what the bill, the TSE, DSFinV-K, the stored invoice and the PDF carry. None of
 * them is touched here. BR-CO-17 instead requires `vat = round(taxable × q)`, and the fiscal split
 * misses it by a cent for some grosses (`0.08 €` at 7 %: net `0.07`, vat `0.01`, but
 * `0.07 × 0.07 = 0.0049 → 0.00`).
 *
 * The obvious repair — keep the group's gross, move a cent between taxable and VAT until BR-CO-17
 * holds — **never succeeds**. In cents, a taxable amount `t` satisfying BR-CO-17 implies the gross
 * `f(t) = t + round(t·q)`. If `f(t) = G` then `|t(1 + q) − G| ≤ ½`, so
 * `|t − G/(1 + q)| ≤ ½/(1 + q) < ½`, so `t = round(G/(1 + q))` — the fiscal net. The fiscal net is
 * therefore the *only* candidate: if its by-subtraction VAT already is `round(net·q)`, nothing needs
 * restating; if it is not, **no** split of that gross satisfies BR-CO-17 (`f` steps by two there and
 * skips `G`; at 19 % `f(2) = 2`, `f(3) = 4`, so `0.03 €` is unreachable). `EInvoiceCentRoundingTest`
 * confirms it by brute force for every gross from 0.01 to 200.00 at 7 % and 19 % — 1,308 and 3,193
 * unreachable grosses, exactly the ones where the fiscal split breaks BR-CO-17.
 *
 * So where the two conflict, EN 16931 validity wins, deliberately and only in `factur-x.xml`:
 * - the group keeps its taxable amount (the sum of its lines, so BR-S-08/BR-CO-10 are untouched) and
 *   states `VAT = round(taxable × rate / 100, 2, HALF_UP)`; its gross is then one cent off the
 *   fiscal gross — **the documented one-cent deviation from the fiscal split** (BT-117, and BT-110 /
 *   BT-112 with it);
 * - BT-112 is the sum of the groups (BR-CO-15), and the drift is given back as the **rounding amount
 *   BT-114**, so the amount due (BT-115 = BT-112 − BT-113 + BT-114, BR-CO-16) is still exactly what
 *   the customer owes and what the PDF prints.
 *
 * The steps, in order:
 * 1. The VAT breakdown is rounded HALF_UP (VAT by subtraction, so each group's gross is kept), then
 *    each group's VAT is set to BR-CO-17's product. The net and tax totals are the groups' sums; the
 *    grand total is the stored one plus the BR-CO-17 drift — never re-derived from the groups, so a
 *    stored grand total that never matched them still fails the invariant check.
 * 2. Document-level allowances and charges (BG-20/21) are rounded to cents; BT-107/BT-108 are their
 *    sums (BR-CO-11/12). A from-bill allowance is already exact to the cent, so this changes nothing.
 * 3. Each VAT group's line nets are rounded, and any cent the rounding lost or gained is handed to
 *    the lines whose rounding moved furthest (largest remainder), so the lines add up to the group's
 *    taxable amount plus its allowances less its charges (BR-S-08 / BR-CO-10). A group off by more
 *    cents than it has lines is stored inconsistently; it is left alone for the amount check to
 *    refuse rather than smeared over. BT-106 is then BT-109 + BT-107 − BT-108 (BR-CO-13).
 * 4. BT-115 is BT-112 − BT-113 + BT-114, so BR-CO-16 holds on the rounded values.
 *
 * Pure: an [EInvoice] in, an [EInvoice] out.
 */
object EInvoiceCentRounding {
    private const val SCALE = 2
    private val CENT = BigDecimal("0.01")

    fun apply(invoice: EInvoice): EInvoice {
        val fiscal = invoice.vatBreakdown.map { it.roundedKeepingGroupTotal() }
        val breakdown = fiscal.map { it.withBrCo17Vat() }
        // Sum of (BR-CO-17 VAT − fiscal VAT) over the groups: what BT-112 moves by and BT-114 gives back.
        val drift = breakdown.sumOf { it.vatAmount } - fiscal.sumOf { it.vatAmount }
        val statedRounding = invoice.roundingAmount?.cents()
        val rounding = if (drift.signum() == 0) statedRounding else (statedRounding ?: BigDecimal.ZERO) - drift
        val grand = invoice.taxInclusiveTotal?.cents()?.plus(drift)
        val prepaid = invoice.prepaidAmount?.cents()
        val allowancesCharges = invoice.allowancesCharges.map { it.copy(amount = it.amount.cents(), baseAmount = it.baseAmount?.cents()) }
        val allowanceTotal = invoice.allowanceTotal?.let { allowancesCharges.filterNot { a -> a.isCharge }.sumOf { a -> a.amount } }
        val chargeTotal = invoice.chargeTotal?.let { allowancesCharges.filter { a -> a.isCharge }.sumOf { a -> a.amount } }
        // With a breakdown, the net and tax totals are the sums of its groups (BR-CO-13/14); the line
        // total adds back what the allowances took off the groups and removes what the charges added.
        val netSum = breakdown.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { acc, g -> acc + g.taxableAmount }
        val taxSum = breakdown.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { acc, g -> acc + g.vatAmount }
        val lineSum = netSum?.let { it + (allowanceTotal ?: BigDecimal.ZERO) - (chargeTotal ?: BigDecimal.ZERO) }
        return invoice.copy(
            vatBreakdown = breakdown,
            lines = reconcileLines(invoice.lines, breakdown, allowancesCharges),
            allowancesCharges = allowancesCharges,
            allowanceTotal = allowanceTotal,
            chargeTotal = chargeTotal,
            lineNetTotal = invoice.lineNetTotal?.let { lineSum ?: it.cents() },
            taxExclusiveTotal = invoice.taxExclusiveTotal?.let { netSum ?: it.cents() },
            taxTotal = invoice.taxTotal?.let { taxSum ?: it.cents() },
            taxInclusiveTotal = grand,
            prepaidAmount = prepaid,
            roundingAmount = rounding,
            amountDue = if (grand != null && prepaid != null) grand - prepaid + (rounding ?: BigDecimal.ZERO) else invoice.amountDue?.cents(),
        )
    }

    /**
     * Rounds the taxable amount and takes the VAT as the group's rounded gross minus it, so
     * `taxable + vat` stays the group's gross — the same by-subtraction rule the till uses. Two
     * independent HALF_UPs would add a cent whenever both sit exactly on a half cent.
     */
    private fun EInvoiceVatBreakdown.roundedKeepingGroupTotal(): EInvoiceVatBreakdown {
        val taxable = taxableAmount.cents()
        return copy(taxableAmount = taxable, vatAmount = (taxableAmount + vatAmount).cents() - taxable)
    }

    /**
     * BR-CO-17's VAT for this group's taxable amount. Unchanged wherever the fiscal split already
     * satisfies it; otherwise one cent off, and — as the class KDoc proves — no other split of the
     * same gross would have satisfied it either. HALF_UP is symmetric about zero, so a credit note's
     * negated group gets exactly the negated VAT.
     */
    private fun EInvoiceVatBreakdown.withBrCo17Vat(): EInvoiceVatBreakdown = copy(vatAmount = En16931AmountRules.vatOf(taxableAmount, rate))

    private fun reconcileLines(
        lines: List<EInvoiceLine>,
        breakdown: List<EInvoiceVatBreakdown>,
        allowancesCharges: List<EInvoiceAllowanceCharge>,
    ): List<EInvoiceLine> {
        val rounded = lines.map { it.copy(lineNetAmount = it.lineNetAmount.cents()) }.toMutableList()
        for (group in breakdown) {
            val members = lines.indices.filter { lines[it].belongsTo(group) }
            if (members.isEmpty()) continue
            val sum = members.fold(BigDecimal.ZERO) { acc, i -> acc + rounded[i].lineNetAmount }
            // The group's lines carry its taxable amount plus the allowances it absorbed, less its charges.
            val adjustment =
                allowancesCharges
                    .filter { it.vatCategoryCode == group.categoryCode && it.vatRate.compareTo(group.rate) == 0 }
                    .fold(BigDecimal.ZERO) { acc, it -> if (it.isCharge) acc - it.amount else acc + it.amount }
            val missingCents = (group.taxableAmount + adjustment - sum).divide(CENT).toInt()
            if (missingCents == 0 || kotlin.math.abs(missingCents) > members.size) continue
            // Remainder = exact − rounded: positive where rounding went down, so those lines take a
            // missing cent first; the most negative give one back first.
            val byRemainder = members.sortedByDescending { lines[it].lineNetAmount - rounded[it].lineNetAmount }
            val takers = if (missingCents > 0) byRemainder else byRemainder.reversed()
            val step = if (missingCents > 0) CENT else CENT.negate()
            takers.take(kotlin.math.abs(missingCents)).forEach { i ->
                rounded[i] = rounded[i].copy(lineNetAmount = rounded[i].lineNetAmount + step)
            }
        }
        // A single unit's price IS its line net; keep `quantity × price = line net` true on the page.
        return rounded.map { line ->
            if (line.quantity.compareTo(BigDecimal.ONE) == 0) line.copy(netUnitPrice = line.lineNetAmount) else line
        }
    }

    private fun EInvoiceLine.belongsTo(group: EInvoiceVatBreakdown) = vatCategoryCode == group.categoryCode && vatRate.compareTo(group.rate) == 0

    private fun BigDecimal.cents(): BigDecimal = setScale(SCALE, RoundingMode.HALF_UP)
}
