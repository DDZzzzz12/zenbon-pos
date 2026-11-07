package org.example.project.money

import kotlinx.serialization.Serializable

/**
 * An amount paired with its currency.
 *
 * Negative amounts are supported and required: the domain models discounts as negative values,
 * and storno, refunds and Zahlartwechsel all depend on signed amounts.
 *
 * Combining two different currencies throws [CurrencyMismatchException] rather than inventing
 * money from a conversion that never happened.
 */
@Serializable
data class Money(
    val amount: Decimal,
    val currency: Currency,
) {
    val isNegative: Boolean get() = amount.isNegative
    val isZero: Boolean get() = amount.isZero

    operator fun plus(other: Money): Money = Money(amount + requireSameCurrency(other).amount, currency)

    operator fun minus(other: Money): Money = Money(amount - requireSameCurrency(other).amount, currency)

    operator fun times(quantity: Int): Money = Money(amount * Decimal.of(quantity), currency)

    operator fun unaryMinus(): Money = Money(-amount, currency)

    fun times(
        factor: Decimal,
        scale: Int,
        rounding: MoneyRounding,
    ): Money = Money(amount.times(factor, scale, rounding), currency)

    fun divide(
        divisor: Decimal,
        scale: Int,
        rounding: MoneyRounding,
    ): Money = Money(amount.divide(divisor, scale, rounding), currency)

    fun setScale(
        scale: Int,
        rounding: MoneyRounding,
    ): Money = Money(amount.setScale(scale, rounding), currency)

    fun abs(): Money = Money(amount.abs(), currency)

    fun toPlainString(): String = amount.toPlainString()

    fun compareTo(other: Money): Int = amount.compareTo(requireSameCurrency(other).amount)

    override fun toString(): String = "${amount.toPlainString()} ${currency.code}"

    internal fun requireSameCurrency(other: Money): Money {
        if (currency != other.currency) {
            throw CurrencyMismatchException(currency.code, other.currency.code)
        }
        return other
    }
}
