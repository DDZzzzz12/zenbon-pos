package org.example.project.features.matching.data.service

/**
 * How much a suggestion can be trusted, and therefore where it sits in the returned list.
 *
 * Declaration order **is** the ranking order — `EXACT` first — so sorting by ordinal is the whole
 * of the ranking rule. Strength is never turned into a score: nothing is auto-applied, so the only
 * thing a strength has to do is order a list an operator reads.
 */
enum class MatchStrength {
    /** The payer used the invoice's own identifier. */
    EXACT,

    /** Identity plus amount, with no identifier — and only where no second reading is possible. */
    STRONG,

    /** Arithmetic agreement alone. Plausible, and never more than that. */
    MEDIUM,
}
