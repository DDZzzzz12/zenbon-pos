package org.example.project.money

/**
 * Binds one currency so call sites do not each have to supply it.
 *
 * Each app constructs this once from its active-currency setting and passes it around, which
 * keeps [Money]'s mandatory currency without threading a [Currency] through every call.
 */
class MoneyFactory(
    val currency: Currency,
) {
    fun of(text: String): Money = Money(Decimal.parse(text), currency)

    fun of(amount: Decimal): Money = Money(amount, currency)

    fun zero(): Money = Money(Decimal.ZERO, currency)
}
