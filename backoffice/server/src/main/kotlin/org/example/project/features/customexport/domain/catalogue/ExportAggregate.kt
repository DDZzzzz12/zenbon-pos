package org.example.project.features.customexport.domain.catalogue

import org.jetbrains.exposed.v1.core.Column

/**
 * One aggregate cell an operator may tick on a to-many relation.
 *
 * A to-many relation cannot be joined — three partners holding four IBANs between them would come out
 * as a seven-row "partner list" with every other column duplicated and nothing in the file to say so.
 * An aggregate is the answer: one correlated scalar subquery per cell, so the row count is whatever
 * the subject says it is and the operator still gets the related data.
 *
 * The rules below are checked in `init` rather than at request time, for the same reason
 * [ExportSubject]'s to-many guard is: an aggregate is a constant written in code, so a bad one should
 * fail the moment the catalogue is class-initialised — at server start, in every test run — and not
 * on the hundredth export of a definition that has been quietly wrong since the day it was declared.
 *
 * @property key this aggregate's own identifier, unique within its table. A stored definition
 *   references it as `<pathKey>~<aggKey>`; see [key]'s guard for why the separator matters.
 * @property function how the related rows are reduced. See [AggregateFunction].
 * @property column the value being aggregated, or null for [AggregateFunction.COUNT], which counts
 *   rows and has no value to read.
 * @property label the German heading offered in the picker, written by hand like [ExportColumn.label].
 * @property type how the cell is rendered. As on [ExportColumn], [ExportFieldType.MONEY] is a
 *   security claim and must be stated deliberately — it skips the CSV formula guard, and the renderer
 *   accepts only a `BigDecimal`. The aggregate's own type, not the column's: a `COUNT` over a money
 *   column is a count.
 */
class ExportAggregate(
    val key: String,
    val function: AggregateFunction,
    val column: Column<*>?,
    val label: String,
    val type: ExportFieldType,
    orderColumn: Column<*>? = null,
) {
    init {
        require(key.isNotBlank()) { "An export aggregate needs a key; a blank one names nothing a definition could store." }

        // The two namespaces are kept disjoint by construction rather than by convention. A column
        // field key is `<prefix>.<column>`; an aggregate field key will be `<pathKey>~<aggKey>`. If an
        // aggregate key could contain a dot, a stored key would be ambiguous about which of the two it
        // names, and the resolver would have to guess — silently, and differently as the catalogue grows.
        require(!key.contains('.')) {
            "Export aggregate key '$key' contains '.', which separates a column field's path from its " +
                "column name. An aggregate key must stay out of that namespace so a stored key can " +
                "never be read as either kind."
        }

        // COUNT counts rows; every other function reduces values, and cannot do so without one.
        // Accepting a column on COUNT would not be harmlessly redundant: `count(x)` skips NULLs while
        // `count(*)` does not, so the declaration would read as one thing and the export would report
        // another — an undercount nobody can see in the file.
        if (function == AggregateFunction.COUNT) {
            require(column == null) {
                "Export aggregate '$key' is a COUNT and must not name a column: it counts related rows, " +
                    "not values, and a column would make it skip the rows whose value is NULL."
            }
        } else {
            require(column != null) {
                "Export aggregate '$key' is a $function and needs the column whose values it reduces."
            }
        }
    }

    /**
     * The column the aggregated values are ordered by, defaulted to [column] for a
     * [AggregateFunction.LIST].
     *
     * Without a deterministic order Postgres may return a LIST's values in any order it likes, so the
     * same definition over unchanged data produces a different cell on the next run, for no visible
     * reason — the kind of difference an advisor diffing two exports has to chase. Ordering by the
     * aggregated column is the right default: it is the value being shown, so the order is the one a
     * reader would have sorted the cell into anyway.
     *
     * Null on every other function, which reduces to a single value and has nothing to order.
     */
    val orderColumn: Column<*>? = orderColumn ?: column.takeIf { function == AggregateFunction.LIST }

    init {
        // Stated as a requirement and not left implicit in the line above: a future edit that changes
        // how the default is derived must still leave a LIST with an order, and this is what says so.
        if (function == AggregateFunction.LIST) {
            require(this.orderColumn != null) {
                "Export aggregate '$key' is a LIST and must be ordered, or the same export renders its " +
                    "values in a different order on the next run."
            }
        }
    }
}
