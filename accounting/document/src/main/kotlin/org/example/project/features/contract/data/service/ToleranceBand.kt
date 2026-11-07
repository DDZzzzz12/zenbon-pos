package org.example.project.features.contract.data.service

import java.math.BigDecimal

/**
 * Whether an actual amount is close enough to the expected one to stay quiet about.
 *
 * Two judgements are baked in, and both are deliberate:
 *
 * 1. **With both tolerances set, the wider one wins.** The allowance is the *maximum* of the two, so
 *    either being satisfied is enough — `tolerance_pct = 10` with `tolerance_abs = 5.00` on an
 *    expected 40,00 gives ±5,00, not ±4,00. Requiring both would let the stricter field silently
 *    govern, which is never what setting two fields means.
 * 2. **With no tolerance at all, the match must be exact.** A fixed rent carries no band, and that is
 *    precisely the contract where a changed amount matters most: 1.450,00 becoming 1.450,01 means the
 *    standing order was altered. That is a true positive, not noise — variable obligations are the
 *    ones that carry a band.
 *
 * The boundary itself is inside the band (`<=`), so exactly at the limit is not yet an alert.
 *
 * [tolerancePct] is in **percent units** (`15.00` = 15 %), matching the DB and `Percentage`; the
 * division by 100 happens here. `BigDecimal` throughout and every comparison via `compareTo`, never
 * `equals` — `BigDecimal("1450.00") != BigDecimal("1450.0000")` under `equals`.
 */
fun isWithinTolerance(
    expected: BigDecimal,
    actual: BigDecimal,
    tolerancePct: BigDecimal?,
    toleranceAbs: BigDecimal?,
): Boolean {
    val deviation = (actual - expected).abs()
    return deviation <= allowedDeviation(expected, tolerancePct, toleranceAbs)
}

/** The wider of the two allowances, treating an unset or negative one as no allowance at all. */
private fun allowedDeviation(
    expected: BigDecimal,
    tolerancePct: BigDecimal?,
    toleranceAbs: BigDecimal?,
): BigDecimal {
    val fromPct = tolerancePct?.let { expected.abs() * it / HUNDRED } ?: BigDecimal.ZERO
    val fromAbs = toleranceAbs ?: BigDecimal.ZERO
    return maxOf(fromPct, fromAbs, BigDecimal.ZERO)
}

private val HUNDRED = BigDecimal(100)
