package org.example.project.features.customexport.domain.catalogue

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.auth.Capability
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID

/**
 * One thing an operator can export a table of.
 *
 * A subject declares its [root] and nothing about columns or joins. Both are derived: the fields come
 * from the registry entry for each table, and the relations from the foreign-key graph. That is what
 * keeps a subject from being a second, drifting statement of facts the schema already holds — and it
 * is why there is no field list here to fall out of step with one.
 *
 * Users still never choose joins, which remains the difference between this and a query builder: the
 * graph decides what is reachable, and cardinality decides in which form. A [Cardinality.ONE] path is
 * offered as ordinary columns ([paths]); a [Cardinality.MANY] one only ever as aggregates
 * ([aggregatePaths]), because joining it would multiply the export's rows.
 *
 * @property dateColumn the single column the date-range filter applies to, or null when the subject
 *   has no natural date. One column, not a choice, so the filter cannot be pointed anywhere.
 */
data class ExportSubject(
    val key: String,
    val label: String,
    /** The table the export counts rows of. Related tables are found from here, not listed here. */
    val root: Table,
    val dateColumn: Column<LocalDateTime>? = null,
    /**
     * The column carrying the shop, when this subject's rows belong to one.
     *
     * The runner always constrains on it. Declared per subject rather than assumed, because the
     * column has a different name and owner on each table — and left to each subject's
     * [basePredicate] it would be forgotten exactly once, which is all it takes for one shop's
     * export to carry another's rows.
     *
     * Null only for a subject whose rows are genuinely global. Say why in the subject's KDoc when so.
     */
    val shopColumn: Column<EntityID<Long>>? = null,
    val toggles: List<ExportToggle> = emptyList(),
    /**
     * Always applied, whatever the operator selected.
     *
     * For rows a subject must never emit regardless of filters — a merged-away business partner is
     * not a company any more, and listing it would show the advisor the duplicate its operator
     * already resolved. Distinct from a [toggle][ExportToggle], which the operator switches.
     */
    val basePredicate: (() -> Op<Boolean>)? = null,
    /**
     * Paths joined on every run, whether or not a field from them was selected.
     *
     * The one escape hatch, and it exists for a real case: a toggle or [basePredicate] reads a
     * column, so its table must be in the query before the filter is built. A path joined on demand
     * cannot carry one.
     */
    val alwaysJoined: List<ExportRelationPath> = emptyList(),
    /**
     * Whether this subject offers the aggregate columns its to-many relations make derivable.
     *
     * True everywhere but one place, and it is a **privacy** switch rather than a technical one — the
     * aggregates it suppresses are perfectly correct and perfectly cheap.
     *
     * ## Why it exists
     *
     * A subject's aggregates fall out of the foreign-key graph: register a table that declares
     * aggregates and every subject that can reach it gains them, with nobody deciding so. On
     * [Personal][org.example.project.features.customexport.domain.catalogue.subjects.StaffSubject]
     * that produced per-employee revenue summaries — Umsatz, Anzahl Ausgangsrechnungen, Erste/Letzte
     * — because `sales_invoice` holds two foreign keys into `employees`.
     *
     * A per-head revenue figure on a staff list is **Leistungs- und Verhaltenskontrolle** in the sense
     * of § 87 Abs. 1 Nr. 6 BetrVG: a means of monitoring performance and conduct, whose introduction
     * needs the works council's agreement. Nobody asked for it; it fell out of the graph. So the
     * subject that summarises *people* offers no summaries.
     *
     * ## What it does not suppress
     *
     * Staff **names** on a bill, cash-book or invoice export. Who booked a receipt is operational
     * fact, already visible on the screen the export mirrors, and is not a performance figure.
     *
     * ## Its relation to the per-path rule — neither is redundant
     *
     * [ExportTable.aggregatesAreMonitoring] closes the same door from the other side: it suppresses
     * every aggregate whose **path crosses** `employees`, from any subject. The two rules do not
     * overlap, because a path never re-enters the table it started from
     * (`SchemaGraph.reentersATableAlreadyOnThePath`):
     *
     * - On **Personal**, `employees` is the root and appears on no hop, so the per-path rule sees
     *   nothing to suppress. This flag is the only thing standing between a staff list and its
     *   per-head Umsatz.
     * - On **Kassenbelege** and **Kassenbuch**, `employees` sits mid-path and this flag is true, so
     *   the per-path rule is the only thing standing there.
     *
     * They also mean different things, which is why one did not replace the other. This flag says
     * *this subject summarises nothing at all* — true of a staff list whatever the graph grows next,
     * including a future to-many path with no connection to employees whatsoever. The per-path rule
     * says *this particular figure is about a person*, wherever it is reached from.
     *
     * ## Before flipping it back
     *
     * A staff list summarises nothing about output — that is the whole point of the flag, not an
     * implementation detail of it. Per-head figures are Leistungsdaten, and offering them wants a
     * works-council agreement (Betriebsvereinbarung) behind it. Flip this deliberately, with that
     * agreement in hand, or not at all.
     *
     * Suppression is enforced twice, because a stored definition outlives the catalogue that offered
     * it: the catalogue stops offering the keys ([allAggregates], [offeringsVisibleTo],
     * [capabilityRequirements]), and the service refuses a definition naming one at save **and** at
     * run — see `CustomExportServiceImpl.suppressedAggregateAmong`. [suppressedAggregates] is what the
     * second half reads.
     */
    val offersAggregates: Boolean = true,
) {
    init {
        // The fail-fast counterpart to the runner's `requireNoToManyPath`. Both exist deliberately:
        // this one catches the *developer* declaring a subject, the runner's catches a *stored
        // definition* whose catalogue changed under it. A subject is a constant written in code, so a
        // bad one should fail the moment it is constructed — not on the hundredth run of an export
        // that has been quietly multiplying rows since the day it was declared.
        //
        // `alwaysJoined` is the open door: [paths] filters to-one, but `alwaysJoined` is taken
        // straight from `SchemaGraph.pathsFrom`, which returns to-many paths too, and a subject that
        // filters it by *target table* rather than by cardinality is one missing clause away from
        // multiplying every row of every run.
        val toMany = alwaysJoined.firstOrNull { it.cardinality == Cardinality.MANY }
        require(toMany == null) {
            "Subject '$key' always-joins the to-many relation '${toMany?.key}'. Joining it multiplies " +
                "every row of the export, duplicating every other column with nothing in the file to " +
                "say so; a to-many relation must be aggregated, not joined."
        }
    }

    /**
     * Relation paths whose columns may be selected. To-one only — a to-many path would multiply rows.
     *
     * Deliberately **not** widened to carry the to-many paths as well. Everything downstream of this
     * list joins what it finds here: [allFields], [offeringsVisibleTo], the `alwaysJoined` guard
     * below and the runner's `requireNoToManyPath` all take "in `paths`" to mean "safe to put in a
     * FROM clause". A to-many path's data arrives instead through [aggregatePaths], as a correlated
     * subquery that leaves the row count alone.
     */
    val paths: List<ExportRelationPath>
        get() = SchemaGraph.pathsFrom(root).filter { it.cardinality == Cardinality.ONE }

    /**
     * Relation paths reached by a to-many edge, which contribute aggregates and never columns.
     *
     * The complement of [paths], and separate from it because the two are used for opposite things:
     * a path here must **never** be joined — that is precisely the row multiplication being avoided —
     * so it must never reach `ExportRelationPath.applyAll` or [alwaysJoined]. Its hops belong inside
     * the correlated subquery the runner builds, where they constrain the aggregated rows instead of
     * the exported ones.
     */
    val aggregatePaths: List<ExportRelationPath>
        get() = SchemaGraph.pathsFrom(root).filter { it.cardinality == Cardinality.MANY }

    /** The root table's own columns, then each joinable path's. Columns only — see [allAggregates]. */
    val allFields: List<ExportField>
        get() = rootFields() + paths.flatMap { it.fields }

    /**
     * Every aggregate cell reachable from [root], one to-many path at a time, minus what [declines].
     *
     * The suppression sits here rather than on [aggregatePaths] on purpose: the paths themselves stay
     * true statements about the schema, and it is only what is *offered* that a subject may decline.
     * The cells withheld are still enumerable through [suppressedAggregates], which is what lets the
     * runner recognise a stored key as suppressed rather than merely unknown.
     */
    val allAggregates: List<ExportAggregateField>
        get() = aggregatePaths.filterNot { declines(it) }.flatMap { it.aggregateFields }

    /**
     * The aggregate cells this subject **declines** to offer — the exact complement of [allAggregates].
     *
     * Non-empty on Personal (which offers none at all) and on every subject reaching an aggregate
     * through `employees` — Kassenbelege and Kassenbuch today.
     *
     * It exists so a refusal can say *why*. Without it a stored definition naming a suppressed key
     * would be indistinguishable from one naming a key the catalogue never had, and the operator would
     * be told their definition is broken rather than that no export summarises figures per person.
     */
    val suppressedAggregates: List<ExportAggregateField>
        get() = aggregatePaths.filter { declines(it) }.flatMap { it.aggregateFields }

    /**
     * Whether this subject withholds [path]'s aggregates, by either of the two rules.
     *
     * One predicate, read by [allAggregates] and [suppressedAggregates], so the offered set and the
     * withheld set are complements by construction rather than by two lists agreeing — a stored key
     * that fell between them would be refused as an unknown field, which is the wrong sentence.
     *
     * The rules are [offersAggregates] (this subject summarises nothing) and
     * [ExportTable.aggregatesAreMonitoring] via [ExportRelationPath.crossesMonitoringTable] (this
     * figure is about a person). Neither implies the other; see [offersAggregates].
     */
    private fun declines(path: ExportRelationPath): Boolean = !offersAggregates || path.crossesMonitoringTable

    /**
     * Everything tickable, of either kind, in picker order.
     *
     * The list a caller that means "what may be exported" should read. [allFields] answers the
     * narrower question "which selected *columns* are there", which the join builder and the legacy
     * key resolver both genuinely want — but a caller after the whole offering and reading that one
     * silently loses every aggregate, so this exists to be the obvious thing to reach for.
     */
    val allOfferings: List<ExportOffering>
        get() = allFields + allAggregates

    /**
     * The offerings this caller may see — columns and aggregates alike.
     *
     * One rule for both kinds, with no exception for aggregates: a relation offering needs the
     * capabilities of every table on its path, not only the one it lives on — see
     * [ExportRelationPath.isVisibleTo]. That an aggregate is read through a subquery rather than a
     * join changes nothing about which tables the query touches, and so changes nothing about what
     * a blank cell would let the reader infer. The root's own columns need the root's capability.
     */
    fun offeringsVisibleTo(held: Set<Capability>): List<ExportOffering> =
        rootFields().filter { it.capability in held } +
            paths.filter { it.isVisibleTo(held) }.flatMap { it.fields } +
            allAggregates.filter { it.path.isVisibleTo(held) }

    /**
     * The capabilities using each offered field requires, keyed by [ExportOffering.key].
     *
     * The one lookup both server-side guards read — the save check and the run check — so they
     * cannot disagree about the same definition. A relation field carries its whole path's
     * [ExportRelationPath.requiredCapabilities]; a root column carries the root table's own.
     *
     * **One map for both kinds**, not two. An aggregate's own [ExportAggregateField.capability] is
     * its table's alone and would under-state what running it costs, so what goes in here is the same
     * path union a column field gets. The `~` in an aggregate key cannot occur in a column key, so
     * the two namespaces share this map without any chance of collision — and that is what stops the
     * save check and the run check from ever resolving one stored key two different ways.
     *
     * A key absent from this map is a field this subject does not offer at all.
     */
    fun capabilityRequirements(): Map<String, Set<Capability>> =
        buildMap {
            rootFields().forEach { put(it.key, setOf(it.capability)) }
            paths.forEach { path -> path.fields.forEach { put(it.key, path.requiredCapabilities) } }
            allAggregates.forEach { put(it.key, it.path.requiredCapabilities) }
        }

    /**
     * The root table's registered columns, keyed by the subject rather than by a path.
     *
     * Empty when the root is unregistered — default-deny holds for a subject's own table too, so a
     * root nobody registered offers nothing rather than everything.
     */
    private fun rootFields(): List<ExportField> {
        val entry = ExportTableRegistry.entryFor(root) ?: return emptyList()
        return entry.columns.map { registered ->
            ExportField(
                key = "$key.${registered.column.name}",
                label = registered.label,
                type = registered.type,
                capability = entry.capability,
                column = registered.column,
                group = null,
                // Verbatim, not prefixed with this subject's key — see `ExportField.legacyKey`.
                legacyKey = registered.legacyKey,
            )
        }
    }
}
