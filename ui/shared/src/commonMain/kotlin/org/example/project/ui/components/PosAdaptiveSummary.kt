package org.example.project.ui.components

/**
 * A summary (Summe/Saldo) row split for the card form: the first non-blank cell is the [label], every
 * later non-blank cell is a value that keeps its column index so it can be captioned with that
 * column's title. Dropping all but one of them would lose totals (a VAT total row carries net, VAT,
 * gross and a count).
 */
data class PosAdaptiveSummary(
    val label: String,
    val values: List<IndexedValue<String>>,
)
