package org.example.project.features.dashboard.presentation.viewmodel

import org.example.project.money.Currency
import org.example.project.money.Decimal
import org.example.project.money.Money
import org.example.project.money.MoneyRounding
import org.example.project.money.Percentage
import org.example.project.money.VatSplit
import org.example.project.money.vatSplitFromGross

/**
 * Billing-specific money math utilities.
 *
 * All arithmetic uses exact [Decimal] with HALF_UP rounding at scale 2 for monetary values.
 */
object BillingMoneyMath {
    /**
     * Parse numpad input into a [Decimal].
     *
     * Normalizes comma to dot for German locale input.
     * Returns null for empty or non-numeric input.
     */
    fun parseInput(text: String): Decimal? {
        if (text.isBlank()) return null
        val normalized = text.replace(",", ".")
        return Decimal.parseOrNull(normalized)
    }

    /**
     * Calculate line total for a selected quantity.
     *
     * @param unitPrice Price per single unit
     * @param selectedQuantity Number of units selected
     * @return Exact multiplication: unitPrice × quantity
     */
    fun selectedLineTotal(
        unitPrice: Decimal,
        selectedQuantity: Int,
    ): Decimal = unitPrice * Decimal.of(selectedQuantity)

    /**
     * Apply a fixed-amount promotion.
     *
     * @param value Raw discount value (e.g., "5.00" for €5 off)
     * @param remaining Remaining bill amount after prior deductions
     * @return Rounded deduction capped to remaining amount
     */
    fun fixedPromotion(
        value: Decimal,
        remaining: Decimal,
    ): Decimal {
        val rounded = value.setScale(2, MoneyRounding.HALF_UP)
        return rounded.coerceAtMost(remaining)
    }

    /**
     * Apply a percentage promotion.
     *
     * @param base Base amount to discount
     * @param percent Discount percentage (e.g., 15 for 15%)
     * @param remaining Remaining bill amount cap
     * @return Charged amount after discount, capped to remaining
     */
    fun percentagePromotion(
        base: Decimal,
        percent: Percentage,
        remaining: Decimal,
    ): Decimal {
        val charged = base.times(Decimal.ONE - percent.toFraction(), 2, MoneyRounding.HALF_UP)
        return charged.coerceAtMost(remaining)
    }

    /**
     * Split a gross amount into net and VAT.
     *
     * @param gross Gross amount
     * @param vatRate VAT rate as a fraction (e.g., "0.19" for 19%)
     * @param currency Currency for the Money objects
     * @return VatSplit with net, vat, and gross
     */
    fun vatSplit(
        gross: Decimal,
        vatRate: Decimal,
        currency: Currency,
    ): VatSplit = Money(gross, currency).vatSplitFromGross(Percentage.ofFraction(vatRate))
}
