package org.example.project.money

/**
 * Sum every amount, verifying the currency matches throughout.
 *
 * @throws MoneyEmptySumException when the collection is empty: there is no currency to return,
 *   and inventing one would be worse than failing. Use [sumOrZero] for the safe form.
 * @throws CurrencyMismatchException when the collection mixes currencies.
 */
fun Iterable<Money>.sum(): Money {
    val iterator = iterator()
    if (!iterator.hasNext()) throw MoneyEmptySumException()
    var total = iterator.next()
    while (iterator.hasNext()) {
        total += iterator.next()
    }
    return total
}

/**
 * Sum every amount, returning zero in [currency] when the collection is empty.
 *
 * @throws CurrencyMismatchException when any element's currency differs from [currency].
 */
fun Iterable<Money>.sumOrZero(currency: Currency): Money {
    var total = Money(Decimal.ZERO, currency)
    for (element in this) {
        total += element
    }
    return total
}

/**
 * Map each element to a [Money] and sum the results.
 *
 * Exists because the stdlib's `sumOf` requires a `Number`, which [Money] deliberately is not.
 *
 * @throws MoneyEmptySumException when the collection is empty.
 */
inline fun <T> Iterable<T>.sumOfMoney(selector: (T) -> Money): Money {
    val iterator = iterator()
    if (!iterator.hasNext()) throw MoneyEmptySumException()
    var total = selector(iterator.next())
    while (iterator.hasNext()) {
        total += selector(iterator.next())
    }
    return total
}

/** Sum every decimal. An empty collection yields [Decimal.ZERO] — no currency ambiguity exists. */
fun Iterable<Decimal>.sum(): Decimal {
    var total = Decimal.ZERO
    for (element in this) {
        total += element
    }
    return total
}
