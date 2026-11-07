package org.example.project.features.customexport.domain.catalogue

import org.example.project.database.CrossAreaForeignKeyCatalog
import org.example.project.database.ManagedTables
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/**
 * Derives what an export may reach from Exposed's own metadata.
 *
 * Nothing here is declared by hand. The foreign keys say which tables are connected and by which
 * columns; the unique indexes say how many rows sit at the far end. Both are already true of the
 * database, which is what makes them a better source than a second statement of the same facts in a
 * catalogue that can drift.
 */
object SchemaGraph {
    /**
     * Following a foreign key **forwards** — from the referencing column to the row it references.
     *
     * Always [Cardinality.ONE]: a foreign key column holds one value, so it points at one row.
     */
    fun cardinalityOfOutgoing(
        @Suppress("UNUSED_PARAMETER") foreignKeyColumn: Column<*>,
    ): Cardinality = Cardinality.ONE

    /**
     * Following a foreign key **backwards** — from a referenced row to the rows referencing it.
     *
     * [Cardinality.ONE] only when the database guarantees at most one such row, i.e. the child column
     * is covered by a unique index of its own. Anything else is [Cardinality.MANY], including the
     * common case of a plain non-unique index, which looks identical in a schema diagram and is the
     * distinction the whole design rests on.
     */
    fun cardinalityOfIncoming(foreignKeyColumn: Column<*>): Cardinality =
        if (isUnique(foreignKeyColumn)) {
            Cardinality.ONE
        } else {
            Cardinality.MANY
        }

    /**
     * Whether [column] alone is guaranteed unique.
     *
     * A **single-column** unique index or a single-column primary key. A composite unique index does
     * not qualify: `UNIQUE (partner_id, iban)` permits many rows per partner, which is precisely the
     * `supplier_bank_account` case, and reading it as a uniqueness guarantee on `partner_id` would
     * reintroduce the row multiplication this method exists to prevent.
     */
    fun isUnique(column: Column<*>): Boolean {
        val table = column.table
        val singleColumnPk = table.primaryKey?.columns?.singleOrNull() == column
        val singleColumnUniqueIndex =
            table.indices.any { index -> index.unique && index.columns.singleOrNull() == column }
        return singleColumnPk || singleColumnUniqueIndex
    }

    /** How many hops a path may take from the subject's root. */
    const val MAX_DEPTH = 2

    /** Whether the export registry knows [table] — and therefore whether the walk may enter it. */
    fun isRegistered(table: Table): Boolean = ExportTableRegistry.entryFor(table) != null

    /**
     * Every relation path reachable from [root], breadth-first, to [MAX_DEPTH].
     *
     * Ordering is `ManagedTables.all`' dependency order, then column order within a table —
     * both fixed — so the same catalogue produces the same SQL on every run.
     *
     * Bounded two ways. [MAX_DEPTH] caps the length — `business_partner.merged_into_partner_id`
     * makes this graph genuinely cyclic, so an unbounded walk would not terminate. Separately, **a
     * path may not enter a table already on it, the root included**; see [reentersATableAlreadyOnThePath].
     *
     * Note that a table may still appear on two *different* paths and contribute to both — the
     * restriction is within one path, not across the result.
     */
    fun pathsFrom(root: Table): List<ExportRelationPath> {
        val rootCapability = ExportTableRegistry.entryFor(root)?.capability
        val drafts = mutableListOf<Draft>()
        // Each frontier entry is a partial path: where it ended, the hops taken, and whether any of
        // those hops was to-many. The flag is carried rather than recomputed — one to-many hop makes
        // the whole path to-many, however safe the hops after it are.
        var frontier = listOf(PartialPath(near = root, hops = emptyList(), cardinality = Cardinality.ONE))
        repeat(MAX_DEPTH) {
            val next = mutableListOf<PartialPath>()
            frontier.forEach { partial ->
                edgesFrom(partial.near).forEach { edge ->
                    if (reentersATableAlreadyOnThePath(root, partial.hops, edge.hop.target)) return@forEach
                    val entry = ExportTableRegistry.entryFor(edge.hop.target) ?: return@forEach
                    val hops = partial.hops + edge.hop
                    val cardinality =
                        if (edge.cardinality == Cardinality.MANY) Cardinality.MANY else partial.cardinality
                    drafts += Draft(hops = hops, cardinality = cardinality, entry = entry)
                    next += PartialPath(near = edge.hop.target, hops = hops, cardinality = cardinality)
                }
            }
            frontier = next
        }
        // Headings are assigned over the finished list rather than inside the walk: whether a heading
        // needs disambiguating is a fact about a subject's paths *together*, and a path being built
        // cannot see its siblings.
        val labels = groupLabelsFor(drafts)
        return drafts.mapIndexed { index, draft ->
            val key = draft.hops.joinToString(".") { segmentOf(it) }
            val label = labels[index]
            ExportRelationPath(
                key = key,
                label = label,
                hops = draft.hops,
                cardinality = draft.cardinality,
                // Column fields only where the path can be joined. A to-many path's columns are not
                // "hidden pending a later slice" — they are unusable as columns at all, because
                // joining the path to read one multiplies the export's rows. Its data is offered
                // instead through `ExportRelationPath.aggregateFields`, which the path derives itself.
                fields = if (draft.cardinality == Cardinality.MANY) emptyList() else fieldsOf(draft.entry, key, label),
                requiredCapabilities =
                    draft.hops.mapNotNull { ExportTableRegistry.entryFor(it.target)?.capability }.toSet() +
                        setOfNotNull(rootCapability),
            )
        }
    }

    /**
     * The picker heading each draft path carries, distinct within one subject by construction.
     *
     * ## The whole path, not the last table
     *
     * A heading is the hops' own labels joined by [HOP_SEPARATOR] — "Personal (ausgestellt von) →
     * Personaldaten" — rather than the far table's label alone. Two reasons, and the second is the
     * one that matters:
     *
     * - **It says which route.** `bill` reaches "Debitor-Rolle" through the invoice it settles *and*
     *   through the invoices raised for it. Both blocks were headed "Debitor-Rolle", and an operator
     *   ticking one from each got a refusal at download time for a choice that looked unambiguous.
     * - **It is a function of the path alone.** Disambiguating only on collision would make a
     *   heading depend on which *other* foreign keys the schema happens to have, so adding one next
     *   month would silently rename headings elsewhere in the picker. Same reasoning as [segmentOf]
     *   spelling out direction and column unconditionally.
     *
     * Stored keys are untouched by any of this: a heading is display, and saved definitions name
     * fields by [ExportRelationPath.key].
     *
     * ## The fallback
     *
     * Two hops still collide when one table is reached by two foreign keys neither of which
     * [ExportRelationLabels] names. The heading then carries the raw segment, which is unmistakable
     * and which `RelationGroupLabelTest` fails on — a colon cannot occur in a hand-written German
     * label, so the test is a simple search. That is deliberate: the fallback keeps the *guarantee*
     * (headings within a subject are distinct) while making the missing entry somebody's failing
     * test rather than an operator's confusing picker.
     */
    private fun groupLabelsFor(drafts: List<Draft>): List<String> {
        val chained = drafts.map { draft -> draft.hops.joinToString(HOP_SEPARATOR) { labelOfHop(it) } }
        val duplicated =
            chained
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        return chained.mapIndexed { index, label ->
            if (label in duplicated) "$label (${segmentOf(drafts[index].hops.last())})" else label
        }
    }

    /** One hop's own name: what [ExportRelationLabels] calls the edge, else the far table's label. */
    private fun labelOfHop(hop: RelationHop): String =
        ExportRelationLabels.forSegment(segmentOf(hop))
            ?: ExportTableRegistry.entryFor(hop.target)?.label
            ?: hop.target.tableName

    /** Between two hops of one heading. An arrow, because a heading is a route and reads as one. */
    const val HOP_SEPARATOR = " → "

    /** A path found by the walk, before it has been given a heading. */
    private data class Draft(
        val hops: List<RelationHop>,
        val cardinality: Cardinality,
        val entry: ExportTable,
    )

    /**
     * Whether following an edge to [candidate] would re-enter a table the path already stands on.
     *
     * This is a **joinability** rule, not a tidiness one, which is why it lives in the walk rather
     * than in the query builder that will consume these paths. `ExportRelationPath.applyTo` folds
     * hops on with Exposed's `leftJoin`, which names the table and nothing else: a second visit
     * would emit the same table twice in one FROM clause with no alias. That is invalid SQL — the
     * run fails outright rather than returning a wrong answer. A path we cannot join is a path we
     * must not *offer*: it would sit in the operator's picker, get ticked, get saved, and then
     * produce a 500 on every run of a definition that looked perfectly reasonable when built.
     *
     * The root counts as visited. A path that walks back to it is also semantically empty — it can
     * only ever land on the very row the export started from, contributing the root's own columns a
     * second time under a second set of keys.
     *
     * This subsumes the narrower "never hop straight back the way we came": an immediate U-turn is
     * just the shortest re-entry.
     *
     * Lifting the restriction means giving each visit its own table alias, throughout the join, the
     * column references and the key scheme. That is a real piece of work, not a small tweak, and it
     * is the thing to do if a genuine two-hop relation through a shared table is ever wanted.
     */
    private fun reentersATableAlreadyOnThePath(
        root: Table,
        hops: List<RelationHop>,
        candidate: Table,
    ): Boolean = candidate == root || hops.any { it.target == candidate }

    /** A path under construction: where it has reached, how, and whether it is already to-many. */
    private data class PartialPath(
        val near: Table,
        val hops: List<RelationHop>,
        val cardinality: Cardinality,
    )

    /** One candidate hop out of a table, with the cardinality of following it in that direction. */
    private data class Edge(
        val hop: RelationHop,
        val cardinality: Cardinality,
    )

    /**
     * Every hop leaving [table]: its outgoing foreign keys, then every foreign key pointing at it.
     *
     * Incoming edges need the whole table list — a table cannot report who references it.
     *
     * Edges [NonTraversableForeignKeys] names are dropped here rather than filtered out of the
     * finished paths, so a banned key cannot appear as a *prefix* of a longer path either: a route
     * the walk may not take at one hop is not one it may take on the way to somewhere else.
     */
    private fun edgesFrom(table: Table): List<Edge> {
        val outgoing =
            table.columns.mapNotNull { column ->
                val referee = refereeOf(column) ?: return@mapNotNull null
                Edge(
                    hop = RelationHop(target = referee.table, from = column, to = referee),
                    cardinality = cardinalityOfOutgoing(column),
                )
            }
        val incoming =
            ManagedTables.all.flatMap { candidate ->
                candidate.columns.mapNotNull { column ->
                    val referee = refereeOf(column) ?: return@mapNotNull null
                    if (referee.table != table) return@mapNotNull null
                    Edge(
                        hop = RelationHop(target = candidate, from = referee, to = column),
                        cardinality = cardinalityOfIncoming(column),
                    )
                }
            }
        return (outgoing + incoming).filterNot { NonTraversableForeignKeys.bans(segmentOf(it.hop)) }
    }

    /**
     * The column [column] references: its Exposed foreign key, or else the declared cross-area
     * foreign key on it ([CrossAreaForeignKeyCatalog]). A cross-area key is a plain id column to Exposed, but
     * the database holds the constraint all the same, so the walk treats it as the edge it always was.
     */
    private fun refereeOf(column: Column<*>): Column<*>? = column.referee ?: CrossAreaForeignKeyCatalog.refereeOf(column)

    /**
     * The path segment a hop contributes: the far table, the direction, and the foreign-key column.
     *
     * The far table's name alone is **not** enough, and this schema already proves it — the two
     * directions of `business_partner.merged_into_partner_id` are two different relations ("the
     * partner this one was merged into" and "the partners merged into this one") that both end at
     * `business_partner`. Naming both `business_partner` would give two paths one key, and one
     * stored export definition would then resolve to whichever the walk happened to emit first.
     *
     * Direction and column are spelled out **unconditionally**, not only where a plain table name
     * would collide. A segment that grows a suffix the day somebody adds a second foreign key would
     * silently rewrite the keys of exports already saved in `export_definition`; the key is stored,
     * so it must depend on the one edge it names and on nothing else in the schema.
     *
     * `via` = we hold the foreign key and follow it forwards; `by` = the far table holds it and we
     * follow it back. `(target, direction, column)` identifies an edge out of a given table
     * uniquely, because a table cannot have two columns of one name.
     */
    internal fun segmentOf(hop: RelationHop): String {
        val outgoing = refereeOf(hop.from) == hop.to
        val direction = if (outgoing) "via" else "by"
        val foreignKeyColumn = if (outgoing) hop.from else hop.to
        return "${hop.target.tableName.lowercase()}:$direction:${foreignKeyColumn.name.lowercase()}"
    }

    /**
     * A registered table's columns as fields of one path, their keys prefixed by it.
     *
     * [group] comes from the path rather than from [entry]: a table reachable twice contributes two
     * sets of fields, and the whole point of the heading is to say which of the two a field is in.
     */
    private fun fieldsOf(
        entry: ExportTable,
        pathKey: String,
        group: String,
    ): List<ExportField> =
        entry.columns.map { registered ->
            ExportField(
                key = "$pathKey.${registered.column.name}",
                label = registered.label,
                type = registered.type,
                capability = entry.capability,
                column = registered.column,
                group = group,
                // Verbatim, not prefixed with [pathKey] — see `ExportField.legacyKey`.
                legacyKey = registered.legacyKey,
            )
        }
}
