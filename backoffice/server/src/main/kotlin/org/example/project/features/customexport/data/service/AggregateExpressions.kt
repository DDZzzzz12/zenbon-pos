package org.example.project.features.customexport.data.service

import kotlinx.datetime.LocalDateTime
import org.example.project.features.customexport.domain.catalogue.AggregateFunction
import org.example.project.features.customexport.domain.catalogue.ExportAggregate
import org.example.project.features.customexport.domain.catalogue.ExportAggregateField
import org.example.project.features.customexport.domain.catalogue.ExportTableRegistry
import org.example.project.features.customexport.domain.catalogue.RelationHop
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.EqOp
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.append
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.select

/**
 * One to-many relation as one cell: the correlated scalar subquery an [ExportAggregateField] becomes.
 *
 * This is the whole point of the aggregate design. A to-many relation **must never be joined** — three
 * partners holding four IBANs between them come out of a join as a seven-row "partner list" with every
 * other column duplicated and nothing in the file to say so. A correlated scalar subquery returns
 * exactly one value per root row and therefore cannot change the row count at all, whatever the
 * related data looks like.
 *
 * The only raw SQL this feature contains lives here, and only because [AggregateFunction.LIST] cannot
 * be expressed any other way — see [StringAgg].
 */
internal object AggregateExpressions {
    /**
     * The subquery for one aggregate field, ready to be selected next to the root's own columns.
     *
     * Built from the path's hops, and from nothing else:
     *
     * - the subquery's **source** is the first hop's target, with every remaining hop folded on as an
     *   **inner** join. Inner, not left, unlike a joined path: a row that does not exist through the
     *   whole chain is not a related row at all, and left-joining would let it into a `count(*)`.
     * - the **correlation** is the first hop alone (`hop.to = hop.from`), whose `from` is the outer
     *   root's column. That single reference to the outer query is what makes this scalar per root row.
     *
     * **No shop predicate is added inside the subquery**, deliberately. The correlation anchors the
     * aggregated rows to one root row, and the outer query has already constrained that row to the
     * current shop; a second predicate would be redundant here and impossible on the tables further
     * down the chain, which carry no shop column of their own.
     *
     * - the **period bound**, on a field the catalogue marked [ExportAggregateField.periodScoped], is
     *   ANDed with that correlation and so applies to the aggregated rows only. It is emphatically not
     *   the outer query's own date filter: that one narrows the *subject's* date column and asks a
     *   different question — partners created in Q1 has nothing to do with invoices issued in Q1 — and
     *   a subject with no date column of its own can still carry a scoped aggregate.
     *
     * This is what finally separates the twins the catalogue offers. `… (Zeitraum)` bounds its
     * subquery, `… (gesamt)` does not, and until now both produced identical SQL under different
     * labels — the worse of the two failures the twin rule exists to prevent, because the label
     * claimed a distinction the file did not carry.
     *
     * @param dateFrom the definition's `dateFrom`, or null. Inclusive, matching the outer query.
     * @param dateTo likewise the definition's `dateTo`, inclusive. "bis 31.12." means the 31st is in.
     */
    fun expressionFor(
        field: ExportAggregateField,
        dateFrom: LocalDateTime?,
        dateTo: LocalDateTime?,
    ): ExpressionWithColumnType<*> {
        val hops = field.path.hops
        check(hops.isNotEmpty()) {
            "Aggregate '${field.key}' has no hops, so there is no relation to correlate its rows through."
        }
        val anchor = hops.first()
        val source =
            hops.drop(1).fold(anchor.target as ColumnSet) { acc, hop ->
                acc.innerJoin(hop.target, { hop.from }, { hop.to })
            }
        val reduced = reductionOf(field.aggregate)
        val predicate = scopedWhere(field, correlation(anchor), dateFrom, dateTo)
        return correlatedSubquery(source.select(reduced).where(predicate), reduced)
    }

    /**
     * The subquery's `WHERE`: the correlation, plus the period bounds when the field is scoped.
     *
     * An unscoped field returns the correlation untouched, and so does a scoped one under a definition
     * that set no Zeitraum — "all time" is what no bound means, and it is the same answer the unscoped
     * twin gives, which is exactly right.
     *
     * A `periodScoped` field whose table declares no `periodColumn` is a **contradiction the catalogue
     * cannot produce**: `ExportRelationPath.twinsOf` only mints the `@period` twin when the registry
     * entry has one. If it ever reached here it would mean the column was removed from the registry
     * while the twin was still being offered, and the honest failure is loud — the alternative is an
     * unbounded subquery under a `(Zeitraum)` heading, a wrong number nothing in the file discloses.
     *
     * One consequence is documented rather than fixed: `ExportTableRegistry.invoiceIssueDate` is cast
     * from a genuinely nullable column, so an invoice with **no** issue date satisfies neither bound
     * and falls out of a scoped aggregate entirely. A scoped COUNT can therefore be lower than its
     * unscoped twin for a reason other than the period. That is the intended answer — there is no date
     * on which to claim such an invoice falls inside the operator's Zeitraum — but a reader comparing
     * the two columns should know it is possible.
     */
    private fun scopedWhere(
        field: ExportAggregateField,
        correlation: Op<Boolean>,
        dateFrom: LocalDateTime?,
        dateTo: LocalDateTime?,
    ): Op<Boolean> {
        if (!field.periodScoped) return correlation
        if (dateFrom == null && dateTo == null) return correlation
        val periodColumn = periodColumnOf(field)
        return buildList {
            dateFrom?.let { add(periodColumn greaterEq it) }
            dateTo?.let { add(periodColumn lessEq it) }
        }.fold(correlation) { acc, bound -> acc and bound }
    }

    /** The column a scoped aggregate is bounded on; see [scopedWhere] for why its absence is fatal. */
    private fun periodColumnOf(field: ExportAggregateField): Column<LocalDateTime> {
        val entry = ExportTableRegistry.entryFor(field.table)
        return checkNotNull(entry?.periodColumn) {
            "Aggregate '${field.key}' is offered as period-scoped, but table '${field.table.tableName}' " +
                "declares no period column to bound it on. Bounding nothing under a '(Zeitraum)' label " +
                "would report a lifetime figure as a period one."
        }
    }

    /**
     * `hop.to = hop.from`, the subquery's one reference to the outer query.
     *
     * [EqOp] rather than the `eq` infix: the two sides are a `Column<*>` pair whose Kotlin types the
     * catalogue does not relate — an `EntityID<Long>` root id against a raw foreign-key column — and
     * the comparison is between the *database* columns a foreign key already declares compatible.
     */
    private fun correlation(anchor: RelationHop): Op<Boolean> = EqOp(anchor.to, anchor.from)

    /**
     * How the related rows reduce to one value, with the column type the reduction yields.
     *
     * The type is carried, not guessed. It decides how the JDBC value is read back, so a `MIN` over a
     * date column returns a `LocalDateTime` and not a driver-specific `Timestamp` whose `toString`
     * would land verbatim in the file, and a `SUM` over `decimal(19,4)` returns the `BigDecimal` the
     * MONEY renderer insists on.
     *
     * The NULL distinction the export depends on is SQL's own and is simply not interfered with:
     * `count(*)` over no rows is `0`, every other reduction over no rows is `NULL` and renders blank.
     * "This partner has no invoices" and "this partner's invoices total nothing" are different facts,
     * and coalescing the second to `0,00` would erase the difference in an advisor's spreadsheet.
     */
    private fun reductionOf(aggregate: ExportAggregate): ExpressionWithColumnType<*> =
        when (aggregate.function) {
            AggregateFunction.COUNT -> CountStar
            AggregateFunction.SUM -> call("SUM", reducedColumnOf(aggregate))
            AggregateFunction.MIN -> call("MIN", reducedColumnOf(aggregate))
            AggregateFunction.MAX -> call("MAX", reducedColumnOf(aggregate))
            AggregateFunction.LIST -> StringAgg(reducedColumnOf(aggregate), orderColumnOf(aggregate))
        }

    /** Non-null for every function but COUNT — `ExportAggregate`'s own init guarantees it. */
    private fun reducedColumnOf(aggregate: ExportAggregate): Expression<*> =
        checkNotNull(aggregate.column) { "Aggregate '${aggregate.key}' reduces values but names no column." }

    /** Likewise guaranteed on a LIST, and only on one. */
    private fun orderColumnOf(aggregate: ExportAggregate): Expression<*> =
        checkNotNull(aggregate.orderColumn) { "A LIST aggregate must be ordered; '${aggregate.key}' is not." }

    /** `NAME(column)`, reading back as whatever the column reads back as. */
    @Suppress("UNCHECKED_CAST")
    private fun call(
        name: String,
        column: Expression<*>,
    ): ExpressionWithColumnType<*> =
        AggregateCall(
            name,
            column,
            (column as ExpressionWithColumnType<*>).columnType as IColumnType<Any>,
        )

    /** The subquery as a value, typed by what it reduces to. */
    @Suppress("UNCHECKED_CAST")
    private fun correlatedSubquery(
        query: AbstractQuery<*>,
        reduced: ExpressionWithColumnType<*>,
    ): ExpressionWithColumnType<*> = CorrelatedSubquery(query, reduced.columnType as IColumnType<Any>)

    /**
     * `(SELECT … )` — a whole query in the position of a value.
     *
     * Exposed ships `wrapAsExpression(query)`, which appends exactly these three parts. It is not used
     * here because it returns a bare `Expression<T?>` with no column type, and Exposed reads a
     * type-less expression straight off the JDBC result set with `getObject` and no conversion. A
     * `MIN(issue_date)` would then arrive as the driver's own timestamp object rather than as a
     * `LocalDateTime`, and the date renderer would write the driver's `toString` into the file. So the
     * wrapper carries the reduction's column type instead, which is the one thing `wrapAsExpression`
     * cannot.
     */
    private class CorrelatedSubquery<T : Any>(
        private val query: AbstractQuery<*>,
        columnType: IColumnType<T>,
    ) : Function<T?>(columnType) {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) =
            queryBuilder {
                append("(")
                query.prepareSQL(this)
                append(")")
            }
    }

    /** `NAME(argument)` for the four standard reductions, carrying the type its result reads back as. */
    private class AggregateCall<T : Any>(
        private val name: String,
        private val argument: Expression<*>,
        columnType: IColumnType<T>,
    ) : Function<T?>(columnType) {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) =
            queryBuilder {
                append(name, "(", argument, ")")
            }
    }

    /**
     * `count(*)`, not `count(column)`.
     *
     * The difference is not cosmetic: `count(column)` skips rows whose value is NULL, so a declaration
     * reading "Anzahl Rechnungen" would quietly report fewer invoices than there are. `ExportAggregate`
     * refuses a column on a COUNT for exactly this reason; this is the other half of that decision.
     */
    private object CountStar : Function<Long>(LongColumnType()) {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder { append("COUNT(*)") }
    }

    /**
     * `string_agg(value, ', ' ORDER BY order)` — **Postgres only**, and the one raw fragment here.
     *
     * Not expressible through Exposed's `CustomFunction`, whose arguments are comma-separated: the
     * `ORDER BY` belongs *inside* the parentheses, after the separator and without a comma, so it is
     * part of the call's syntax rather than another argument. Hence a hand-written `toQueryBuilder`.
     *
     * The ordering is not decoration. Without it Postgres may return the values in any order it likes,
     * so the same definition over unchanged data produces a different cell on the next run — the kind
     * of difference an advisor diffing two exports has to chase. `ExportAggregate` guarantees a LIST
     * carries one.
     *
     * H2 has no `string_agg`, so a LIST field cannot be exercised by the default test task; it is
     * covered by the `@Tag("postgres")` leg instead.
     */
    private class StringAgg(
        private val value: Expression<*>,
        private val order: Expression<*>,
    ) : Function<String?>(TextColumnType()) {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) =
            queryBuilder {
                append("string_agg(", value, ", '", SEPARATOR, "' ORDER BY ", order, ")")
            }

        private companion object {
            /**
             * What separates two values in one cell.
             *
             * A literal in the SQL rather than a bound parameter, and safe as one because it is this
             * constant and never anything an operator can influence.
             */
            const val SEPARATOR = ", "
        }
    }
}
