package org.example.project.features.customexport.domain.catalogue

/** How a value is rendered into the file. */
enum class ExportFieldType {
    TEXT,
    DATE,
    BOOLEAN,

    /**
     * A value read from a numeric database column.
     *
     * [NUMBER] and [MONEY] are written as `CsvValue.Numeric`, which **skips the CSV formula guard** —
     * otherwise every negative amount would be prefixed with `'` and Excel would import it as text
     * that will not sum.
     *
     * That makes the type a security statement, not a formatting hint: only give it to a value read
     * straight from a numeric column. A field an operator can influence — a free-text amount, a
     * numeric column that can hold `"n/a"`, anything overridable — must be [TEXT], because the writer
     * cannot check the classification and an unguarded operator string reaches Excel and is evaluated.
     */
    NUMBER,

    /** As [NUMBER], and rendered through the `:platform-money` decimal path, never a `Double`. See `platform/money/README.md`. */
    MONEY,
}
