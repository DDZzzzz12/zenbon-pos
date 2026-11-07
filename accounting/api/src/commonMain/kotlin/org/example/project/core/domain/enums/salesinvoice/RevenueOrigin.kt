package org.example.project.core.domain.enums.salesinvoice

/**
 * Where the revenue on this invoice was already recorded — which decides whether it may post to the
 * DATEV Debitoren export, and whether it carries a receivable.
 *
 * - [POS_SETTLED] — the bill was paid at the till and is in the Z-Bon. The invoice is a snapshot:
 *   `open_amount` is 0 from birth and it must **never** post revenue to AR, or the sale is counted
 *   twice.
 * - [POS_RECEIVABLE] — the bill was settled with a receivable-creating payment type. Revenue is in
 *   the Z-Bon as `Forderungsentstehung`; the later bank credit clears the receivable rather than
 *   being new revenue. Produced from slice 4c onward.
 * - [STANDALONE] — never touched the till. This is the only kind whose revenue the AR export books.
 *   Produced from slice 4b onward.
 */
enum class RevenueOrigin {
    POS_SETTLED,
    POS_RECEIVABLE,
    STANDALONE,
}
