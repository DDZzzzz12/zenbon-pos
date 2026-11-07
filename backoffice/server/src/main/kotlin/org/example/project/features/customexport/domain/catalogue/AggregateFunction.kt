package org.example.project.features.customexport.domain.catalogue

/**
 * How several related rows are reduced to one cell.
 *
 * Every one of these is emitted as a correlated scalar subquery, never a join — that is what keeps a
 * to-many relation from multiplying the export's rows.
 */
enum class AggregateFunction {
    COUNT,
    SUM,
    LIST,
    MIN,
    MAX,
}
