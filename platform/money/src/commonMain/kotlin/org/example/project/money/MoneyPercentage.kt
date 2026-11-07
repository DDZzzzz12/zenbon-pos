package org.example.project.money

/** 19 % of 100.00 -> 19.00. */
fun Money.percentageOf(
    rate: Percentage,
    scale: Int = 2,
    rounding: MoneyRounding = MoneyRounding.HALF_UP,
): Money = Money(amount.times(rate.toFraction(), scale, rounding), currency)

/** Markup / net to gross: 100.00 + 19 % -> 119.00. */
fun Money.plusPercentage(
    rate: Percentage,
    scale: Int = 2,
    rounding: MoneyRounding = MoneyRounding.HALF_UP,
): Money =
    Money(
        amount.times(Decimal.ONE + rate.toFraction(), scale, rounding),
        currency,
    )

/** Discount: 100.00 - 10 % -> 90.00. */
fun Money.minusPercentage(
    rate: Percentage,
    scale: Int = 2,
    rounding: MoneyRounding = MoneyRounding.HALF_UP,
): Money =
    Money(
        amount.times(Decimal.ONE - rate.toFraction(), scale, rounding),
        currency,
    )

/** Share of a total: 25.00 of 200.00 -> 12.50 %. */
fun Money.ratioTo(
    whole: Money,
    scale: Int = 2,
    rounding: MoneyRounding = MoneyRounding.HALF_UP,
): Percentage {
    requireSameCurrency(whole)
    if (whole.isZero) throw DecimalArithmeticException("ratio to a zero total is undefined")
    val fraction = amount.divide(whole.amount, scale + 2, rounding)
    return Percentage((fraction * Decimal.HUNDRED).setScale(scale, rounding))
}
