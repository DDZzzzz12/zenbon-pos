package org.example.project.features.allocation.data.service

import java.math.BigDecimal

/** The scale every residual is pinned to, matching `DECIMAL(19,4)` in the database. */
private const val RESIDUAL_SCALE = 4

/**
 * What is still open on [total] after [allocated] — the one residual formula, used for both an
 * invoice and a bank transaction.
 *
 * Returns null when [total] is null: "we do not know what is owed" is not the same fact as "nothing
 * is owed", and collapsing them would mark an amount-less invoice as settled.
 *
 * The result is **not clamped at zero**. Over-allocating €120 against a €100 invoice is a real
 * mistake, and a residual of exactly zero would look identical to a correctly settled one. Negative
 * is visible.
 *
 * Scale is pinned rather than inherited so two equal residuals compare equal with `equals` as well as
 * `compareTo` — `BigDecimal.equals` is scale-sensitive, and these values are compared in tests and
 * stored in a fixed-scale column.
 *
 * Pure: no I/O, no database.
 */
fun residualOf(
    total: BigDecimal?,
    allocated: List<BigDecimal>,
): BigDecimal? {
    val gross = total ?: return null
    val sum = allocated.fold(BigDecimal.ZERO) { acc, it -> acc + it }
    return gross.subtract(sum).setScale(RESIDUAL_SCALE)
}
