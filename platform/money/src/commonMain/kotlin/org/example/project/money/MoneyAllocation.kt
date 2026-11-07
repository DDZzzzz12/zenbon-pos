package org.example.project.money

/**
 * Split into [parts] equal shares whose sum is exactly this amount.
 *
 * Naive division loses money: 10.00 / 3 rounded to 2 decimals is 3.33, and 3.33 * 3 = 9.99.
 * Here the remainder is distributed one minor unit at a time to the FIRST parts, so
 * `10.00.allocate(3)` is `[3.34, 3.33, 3.33]`. Any two parts differ by at most one minor unit.
 *
 * Computed on the absolute value and negated afterwards, so allocation is sign-symmetric —
 * `(-m).allocate(n) == m.allocate(n).map { -it }` — which matters for storno and refund totals.
 *
 * @throws MoneyAllocationException when [parts] < 1, or when this amount's scale is finer than
 *   [scale] (the caller must round explicitly; allocation never rounds silently).
 */
fun Money.allocate(
    parts: Int,
    scale: Int = 2,
): List<Money> {
    if (parts < 1) throw MoneyAllocationException("parts must be >= 1 but was $parts")
    val units = minorUnits(scale)
    val negative = units < 0L
    val magnitude = if (negative) -units else units

    val base = magnitude / parts
    val remainder = (magnitude % parts).toInt()

    return List(parts) { index ->
        val value = if (index < remainder) base + 1L else base
        moneyOfUnits(if (negative) -value else value, scale)
    }
}

/**
 * Split proportionally to [weights], with a sum exactly equal to this amount.
 *
 * Uses the largest-remainder (Hamilton) method: each recipient gets its truncated exact share,
 * then the leftover units go one each to the entries with the largest fractional remainder, ties
 * broken by lowest index. Fully deterministic, as fiscal reproducibility requires.
 *
 * A zero weight receives exactly zero, mirroring the server's `share == 0 -> skip` behaviour.
 *
 * @throws MoneyAllocationException when [weights] is empty, contains a negative value, sums to
 *   zero, or when this amount's scale is finer than [scale].
 */
fun Money.allocateByWeights(
    weights: List<Decimal>,
    scale: Int = 2,
): List<Money> {
    if (weights.isEmpty()) throw MoneyAllocationException("weights must not be empty")
    if (weights.any { it.isNegative }) {
        throw MoneyAllocationException("weights must all be non-negative")
    }
    val totalWeight = weights.reduce { a, b -> a + b }
    if (totalWeight.isZero) throw MoneyAllocationException("weights must sum to more than zero")

    val units = minorUnits(scale)
    val negative = units < 0L
    val magnitude = if (negative) -units else units
    val magnitudeDecimal = Decimal.of(magnitude)

    // Exact share per entry, at enough scale to rank remainders deterministically.
    val shareScale = DecimalEnvelope.MAX_SCALE
    val truncated = ArrayList<Long>(weights.size)
    val remainders = ArrayList<Decimal>(weights.size)

    for (weight in weights) {
        val exact = (magnitudeDecimal * weight).divide(totalWeight, shareScale, MoneyRounding.DOWN)
        val floor = exact.setScale(0, MoneyRounding.DOWN)
        truncated += floor.toPlainString().toLong()
        remainders += exact - floor
    }

    var leftover = magnitude - truncated.sum()

    // Distribute leftover units to the largest remainders; ties go to the lower index.
    val order =
        weights.indices.sortedWith(
            compareByDescending<Int> { remainders[it] }.thenBy { it },
        )
    var cursor = 0
    while (leftover > 0L && cursor < order.size) {
        truncated[order[cursor]] = truncated[order[cursor]] + 1L
        leftover--
        cursor++
    }

    return truncated.map { moneyOfUnits(if (negative) -it else it, scale) }
}

/** This amount as an integer count of 10^-[scale] units. */
private fun Money.minorUnits(scale: Int): Long {
    if (amount.scale > scale) {
        throw MoneyAllocationException(
            "amount scale ${amount.scale} is finer than allocation scale $scale; " +
                "round explicitly before allocating",
        )
    }
    val shifted = amount.setScale(scale, MoneyRounding.HALF_UP).toPlainString()
    return shifted.replace(".", "").toLong()
}

private fun Money.moneyOfUnits(
    units: Long,
    scale: Int,
): Money = Money(Decimal.of(units, scale), currency)
