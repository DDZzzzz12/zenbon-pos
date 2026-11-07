package org.example.project.export.csv

/**
 * What one cell of a spreadsheet export means, as stated by the caller.
 *
 * [SpreadsheetCsvWriter] cannot tell an operator-entered string from an amount it rendered itself —
 * both arrive as a `String` — yet the two need different treatment for the formula guard. So the
 * caller, which knows the field's type, says which it is.
 */
sealed interface CsvValue {
    /** Operator-entered text. Formula-guarded, because it can contain anything. */
    data class Text(
        val value: String,
    ) : CsvValue

    /**
     * A value this system rendered from a numeric column — already carrying its decimal comma.
     *
     * Not formula-guarded: it cannot contain a formula, and guarding it would prefix every negative
     * amount with `'`, which Excel then imports as text. A column of amounts that will not sum is
     * the defect this distinction exists to prevent.
     */
    data class Numeric(
        val rendered: String,
    ) : CsvValue
}
