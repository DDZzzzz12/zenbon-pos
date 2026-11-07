package org.example.project.features.salesinvoice.domain.repository

/**
 * Hands out Ausgangsrechnung numbers.
 *
 * **Requires an ambient transaction, and that is load-bearing**: the counter increments inside the
 * caller's transaction, so an issuance that fails afterwards returns the number rather than leaving
 * a gap in the sequence.
 */
interface ArNumberSequenceRepository {
    /**
     * The next number for [prefix] and [year], formatted `PREFIX-YEAR-NNNNN`.
     *
     * @param startAt seeds the counter when no row exists yet for this (prefix, year) — a shop
     *   continuing an existing sequence starts at its next number. Ignored once the counter runs;
     *   it can never reset one.
     * @param padding the minimum width of the numeric part. A number wider than [padding] is
     *   printed in full — padding is a floor, never a truncation.
     */
    fun nextNumber(
        prefix: String,
        year: Int,
        startAt: Long = 1L,
        padding: Int = 5,
    ): String
}
