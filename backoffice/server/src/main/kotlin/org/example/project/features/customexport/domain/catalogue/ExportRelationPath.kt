package org.example.project.features.customexport.domain.catalogue

import org.example.project.core.domain.enums.auth.Capability
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.leftJoin

/**
 * A named chain of hops from a subject's root to a related table, with the columns it contributes.
 *
 * @property key the path prefix a stored field key carries — see `SchemaGraph.segmentOf` for the
 *   shape of one segment. The path, not the table, is the identity: a table reachable two ways
 *   contributes two distinct sets of columns, which is what keeps their keys from colliding.
 * @property requiredCapabilities every table on the path, not only the last. Traversing a table you
 *   may not read would leak its contents by inference — a blank cell at the end of a two-hop path
 *   tells you the middle row does not exist.
 */
data class ExportRelationPath(
    val key: String,
    /**
     * The picker heading this path's fields and aggregates sit under.
     *
     * Assigned by `SchemaGraph.groupLabelsFor` over the whole subject, **not** copied from the far
     * table's registry label: within one subject two paths reaching one table must not share a
     * heading, or an operator ticks a column from each and the run is refused for a choice that
     * looked unambiguous. Display only — a stored definition names fields by [key].
     */
    val label: String,
    val hops: List<RelationHop>,
    val cardinality: Cardinality,
    val fields: List<ExportField>,
    val requiredCapabilities: Set<Capability>,
) {
    /**
     * The aggregate cells this path offers, empty on every [Cardinality.ONE] path.
     *
     * Kept as a **second list** rather than folded into [fields]: the two are consumed differently —
     * a column field becomes a selected column, an aggregate becomes a correlated scalar subquery —
     * and one list would make every caller filter by type before it could do either. Two names for
     * two kinds keeps the runner's dispatch total.
     *
     * Aggregates attach to to-many paths **only**. Over a to-one path the far row is already
     * joinable, so a count of it is always 0 or 1 and a sum of it is the value itself: an aggregate
     * there answers a question the plain column already answered, under a second key. The converse
     * holds too — a to-many path contributes no column fields at all, because joining it is the row
     * multiplication this whole design exists to prevent.
     *
     * Derived rather than passed in, exactly as [target] is: the path knows the table it ends at, and
     * the registry entry for that table already states its aggregates. A constructor parameter would
     * be a second statement of the same facts, free to drift from the first. Lazy so that an
     * aggregate field can carry the finished path it belongs to without the two having to be built in
     * an impossible order.
     */
    val aggregateFields: List<ExportAggregateField> by lazy { buildAggregateFields() }

    /**
     * Whether any hop on this path lands on a table whose per-row summaries are monitoring data.
     *
     * **Any** hop, not the last one: an aggregate reached *through* `employees` is grouped by the
     * person that hop selected, so `bill → employees → sales_invoice` yields "Umsatz der Person, die
     * diesen Beleg gebucht hat" — the same per-head league table a staff export would have shown,
     * under a different subject's heading. The table in the middle is what makes the figure personal,
     * so the middle is where the walk has to look.
     *
     * Column fields are deliberately **not** filtered by this: see
     * [ExportTable.aggregatesAreMonitoring]. Names stay, summaries go.
     *
     * A path with no registry entry for a hop cannot occur — `SchemaGraph.pathsFrom` refuses to enter
     * an unregistered table — so an absent entry reads as "not monitoring" rather than as a hole.
     */
    val crossesMonitoringTable: Boolean
        get() = hops.any { ExportTableRegistry.entryFor(it.target)?.aggregatesAreMonitoring == true }

    private fun buildAggregateFields(): List<ExportAggregateField> {
        if (cardinality != Cardinality.MANY) return emptyList()
        val entry = ExportTableRegistry.entryFor(target) ?: return emptyList()
        return entry.aggregates.flatMap { aggregate -> twinsOf(entry, aggregate) }
    }

    /**
     * One aggregate as the field(s) an operator may tick — **two** when its table has a period column.
     *
     * Either one alone misleads. Offer only the unscoped figure and it lands in a file the operator
     * built with a Zeitraum, answering over all time with nothing in the column to say so. Offer only
     * the scoped one and the lifetime figure — what a creditor list is most often asked for — becomes
     * unreachable, and an operator wanting it widens the Zeitraum until the two coincide, which
     * changes the rest of the export too. So both are offered, and each says in its own label which
     * it is: `(Zeitraum)` or `(gesamt)`.
     *
     * A table with no period column has no such ambiguity to disclose: its rows are not events, so
     * there is nothing for a Zeitraum to constrain. It yields exactly one field, bare-labelled and
     * keyed without the `@period` suffix.
     */
    private fun twinsOf(
        entry: ExportTable,
        aggregate: ExportAggregate,
    ): List<ExportAggregateField> {
        if (entry.periodColumn == null) {
            return listOf(aggregateField(entry, aggregate, periodScoped = false, keySuffix = "", labelSuffix = ""))
        }
        return listOf(
            aggregateField(entry, aggregate, periodScoped = true, keySuffix = "@period", labelSuffix = " (Zeitraum)"),
            aggregateField(entry, aggregate, periodScoped = false, keySuffix = "", labelSuffix = " (gesamt)"),
        )
    }

    /**
     * One aggregate field of this path.
     *
     * The key is `<pathKey>~<aggKey>`: `~` cannot appear in a table or column name, so the aggregate
     * namespace and the `<prefix>.<column>` namespace are disjoint by construction and both kinds can
     * share one capability map. The capability is the owning table's, exactly as its columns carry
     * it; the path-level union stays in [requiredCapabilities], where the existing machinery reads it.
     */
    private fun aggregateField(
        entry: ExportTable,
        aggregate: ExportAggregate,
        periodScoped: Boolean,
        keySuffix: String,
        labelSuffix: String,
    ): ExportAggregateField =
        ExportAggregateField(
            key = "$key~${aggregate.key}$keySuffix",
            label = "${aggregate.label}$labelSuffix",
            type = aggregate.type,
            capability = entry.capability,
            // The path's heading, not the table's own: a table reachable twice must not put both
            // sets of aggregates under one heading, exactly as its columns must not — see
            // `SchemaGraph.groupLabelsFor`.
            group = label,
            table = entry.table,
            aggregate = aggregate,
            path = this,
            periodScoped = periodScoped,
        )

    /**
     * Folds this path's hops onto [source] as LEFT joins, in order.
     *
     * The only place a join is constructed, so there is no second spelling of it to disagree.
     */
    fun applyTo(source: ColumnSet): ColumnSet = hops.fold(source) { acc, hop -> acc.leftJoin(hop.target, { hop.from }, { hop.to }) }

    /**
     * Whether a caller holding [held] may be offered — and may run — this path's fields.
     *
     * The rule in one sentence: **if the query touches a table, the caller must hold that table's
     * capability**, not only the tables whose columns they read. Holding the far table's capability
     * is not enough, because reaching a value at the end of a path proves the intermediate rows
     * exist: on `invoice → supplier → business_partner` a caller without `supplier`'s capability
     * could read a blank cell and learn which partners carry a creditor role, a table never shown to
     * them.
     *
     * Total rather than case-by-case, and deliberately so. The alternative is reasoning afresh, for
     * every path the graph grows next, about whether a blank cell says something it should not —
     * analysis that is right three times and wrong the fourth.
     */
    fun isVisibleTo(held: Set<Capability>): Boolean = held.containsAll(requiredCapabilities)

    /** The capability of [requiredCapabilities] this caller lacks, or null when they hold them all. */
    fun missingCapabilityFor(held: Set<Capability>): Capability? = requiredCapabilities.firstOrNull { it !in held }

    /** The table this path ends at — where its fields live. */
    val target: Table get() = hops.last().target

    companion object {
        /**
         * Folds several paths onto [source], joining each table **at most once**.
         *
         * A subject's path list holds a path and its own prefixes as separate entries — `supplier` and
         * `supplier → business_partner` are two paths over three hops, two of which are the same hop.
         * Folding them independently would emit `supplier` twice in one FROM clause with no alias,
         * which is invalid SQL rather than a wrong answer. Hops keep their order, so a two-hop path's
         * second join always finds the table its condition reads already in the query.
         *
         * The dedupe is only sound because the repeated hop is the **same** hop. Two paths reaching
         * one table by *different* hops are a different thing entirely, and silently keeping the
         * first route would make the second path's fields read the first route's row — a wrong value
         * in a cell, with nothing to say so. [tableJoinedTwoWays] finds that case; callers check it
         * first so the operator gets a sentence rather than a stack trace, and the [check] here is
         * the backstop that keeps a future caller from reintroducing the silent pick.
         *
         * Lifting the restriction means giving each visit its own table alias, threaded through the
         * join, the column references and the stored key scheme — the same real piece of work
         * `SchemaGraph.reentersATableAlreadyOnThePath` names, and deliberately out of scope here.
         */
        fun applyAll(
            source: ColumnSet,
            paths: List<ExportRelationPath>,
        ): ColumnSet {
            val conflict = tableJoinedTwoWays(paths)
            check(conflict == null) {
                "Two relation paths reach '${conflict?.tableName}' by different foreign keys; joining " +
                    "it once would make one path's columns read the other path's row"
            }
            val joined = mutableMapOf<Table, RelationHop>()
            return paths.flatMap { it.hops }.fold(source) { acc, hop ->
                if (joined.put(hop.target, hop) == null) acc.leftJoin(hop.target, { hop.from }, { hop.to }) else acc
            }
        }

        /**
         * The first table [paths] would reach by two **different** hops, or null when none does.
         *
         * The same table by an identical hop is the prefix case [applyAll] exists to dedupe and is
         * not a conflict. Different hops are: one FROM clause can hold the table once, so one of the
         * two routes would be dropped, and the fields of the dropped one would then read the
         * surviving route's row.
         *
         * Separate from [applyAll] so the refusal can be worded for an operator at the point that
         * knows which definition provoked it.
         */
        fun tableJoinedTwoWays(paths: List<ExportRelationPath>): Table? {
            val seen = mutableMapOf<Table, RelationHop>()
            paths.flatMap { it.hops }.forEach { hop ->
                val existing = seen.putIfAbsent(hop.target, hop)
                if (existing != null && existing != hop) return hop.target
            }
            return null
        }
    }
}
