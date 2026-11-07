package org.example.project.features.customexport.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.export.csv.CsvValue
import org.example.project.export.csv.SpreadsheetCsvWriter
import org.example.project.features.customexport.database.tables.ExportDefinitionFieldTable
import org.example.project.features.customexport.database.tables.ExportDefinitionTable
import org.example.project.features.customexport.domain.catalogue.Cardinality
import org.example.project.features.customexport.domain.catalogue.ExportAggregateField
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportField
import org.example.project.features.customexport.domain.catalogue.ExportFieldResolver
import org.example.project.features.customexport.domain.catalogue.ExportFieldType
import org.example.project.features.customexport.domain.catalogue.ExportOffering
import org.example.project.features.customexport.domain.catalogue.ExportRelationPath
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.customexport.domain.models.ExportDefinition
import org.example.project.features.customexport.domain.models.ExportDefinitionField
import org.example.project.features.customexport.domain.service.CustomExportService
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.TestOnlyApi
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * See [CustomExportService]. Every method that touches a table does so inside its own [dbQuery] —
 * routes hold no transaction.
 *
 * The class has one recurring shape worth naming: **validation happens on write, brokenness is
 * decided on read**. A key that does not resolve is refused at save time, because the catalogue is
 * known then and storing a key we already know is wrong helps nobody. A key that stops resolving
 * *later* — because the catalogue changed under a definition that was valid when it was saved — is
 * not a rejection but a fact, reported on the definition so the operator can see it, fix it or delete
 * it. Running it with the columns that still resolve is the one option not on the table: a CSV that
 * is quietly one column short is worse than no CSV.
 */
class CustomExportServiceImpl(
    /**
     * The most data rows one run will write.
     *
     * A parameter rather than a constant so a test can prove the truncation path without inserting
     * a hundred thousand partners; production never passes it. It is a guard on this process's
     * memory — the whole file is built in a `ByteArrayOutputStream` before anything is streamed —
     * not a paging feature, which is why an operator cannot raise it.
     */
    private val rowCap: Int = DEFAULT_ROW_CAP,
    /**
     * The most tables one run may join.
     *
     * Depth 2 plus default-deny already bounds this; the cap is what stops a later registry expansion
     * from turning one click into a join nobody reviewed. A parameter so a test can drive the refusal
     * without a wide schema; production never passes it.
     */
    private val maxJoinedTables: Int = DEFAULT_MAX_JOINED_TABLES,
    /**
     * The most aggregate columns one definition may select.
     *
     * Applied at **save** and again at **run**, and the second is not redundant: this is a
     * constructor parameter, so a definition saved under one value is run under whatever the running
     * process holds. Save-time refusal is what stops an operator storing a definition the runner will
     * reject; run-time refusal is what keeps the bound true for definitions saved before it.
     *
     * Unlike an ordinary column, which is read off a row the query was fetching anyway, every
     * aggregate is a correlated scalar subquery evaluated **once per row**. At [rowCap] that is a
     * hundred thousand subqueries per aggregate column, so the cost of ticking one more is a whole
     * further pass over the related table. Ordinary columns stay uncapped for the same reason: they
     * cost nothing per row.
     *
     * A parameter so a test can drive the refusal without a definition wide enough to hit the real
     * limit — a guard no test can reach is a guard nobody knows works. Production never passes it.
     */
    private val maxAggregateColumns: Int = DEFAULT_MAX_AGGREGATE_COLUMNS,
) : CustomExportService {
    /**
     * No [dbQuery]: the catalogue is declared in Kotlin, not reflected from the schema, so this
     * method reads no table.
     */
    override suspend fun catalogue(held: Set<Capability>): List<CustomExportDto.SubjectDto> =
        ExportCatalogue.visibleTo(held).map { visible ->
            CustomExportDto.SubjectDto(
                key = visible.subject.key,
                label = visible.subject.label,
                fields =
                    visible.fields.map { field ->
                        CustomExportDto.FieldDto(
                            key = field.key,
                            label = field.label,
                            // The enum *name*, not its ordinal — see the DTO container's KDoc.
                            type = field.type.name,
                            group = field.group,
                            // Read off the offering's type, never off the `~` in its key: the key's
                            // shape is a server-side encoding the client must not depend on.
                            isAggregate = field is ExportAggregateField,
                        )
                    },
                toggles = visible.subject.toggles.map { CustomExportDto.ToggleDto(it.key, it.label) },
                hasDateFilter = visible.subject.dateColumn != null,
                // Service-wide, repeated per subject: the picker renders one subject at a time and
                // gates on this rather than on a constant of its own that would drift from ours.
                maxAggregateColumns = maxAggregateColumns,
            )
        }

    override suspend fun list(): List<CustomExportDto.DefinitionDto> =
        dbQuery {
            ExportDefinition
                .find { ExportDefinitionTable.shopId eq currentShop().id }
                .orderBy(ExportDefinitionTable.name to SortOrder.ASC)
                .map { it.toDto() }
        }

    /**
     * Looked up by id alone, without the shop constraint [list] applies.
     *
     * A known single-shop assumption, and deliberately the same one `SupplierServiceImpl`'s
     * `requireSupplier` makes. Diverging here would leave the codebase with two conventions and no
     * recorded decision; multi-shop work has to sweep every such lookup together, and should find
     * them consistent when it does.
     */
    override suspend fun get(id: Long): CustomExportDto.DefinitionDto? =
        dbQuery {
            ExportDefinition.findById(id)?.toDto()
        }

    override suspend fun save(
        request: CustomExportDto.SaveDefinitionRequestDto,
        held: Set<Capability>,
        employeeId: String?,
    ): CustomExportDto.DefinitionDto {
        val subject = validated(request, held)
        val dateFrom = parseBound(request.dateFrom, "dateFrom")
        val dateTo = parseBound(request.dateTo, "dateTo")

        return dbQuery {
            val definition =
                ExportDefinition.new {
                    this.shop = currentShop()
                    this.createdByEmployeeId = employeeId
                    this.writeHeader(request, subject, dateFrom, dateTo)
                }
            writeFields(definition, request.fields)
            definition.toDto()
        }
    }

    /**
     * Replaces a stored definition in place — same id, same author, same shop, same subject.
     *
     * Runs exactly the validation [save] runs, through the one [validated], so an edit cannot store
     * what a fresh save would refuse. The columns are replaced wholesale rather than diffed: the
     * request *is* the whole column list, and a diff would be a second place for position clashes to
     * arise. Before this existed the editor could only POST, so every edit silently saved a copy.
     */
    override suspend fun update(
        id: Long,
        request: CustomExportDto.SaveDefinitionRequestDto,
        held: Set<Capability>,
    ): CustomExportDto.DefinitionDto {
        val subject = validated(request, held)
        val dateFrom = parseBound(request.dateFrom, "dateFrom")
        val dateTo = parseBound(request.dateTo, "dateTo")

        return dbQuery {
            val definition = ExportDefinition.findById(id) ?: throw definitionNotFound(id)
            // The server half of the editor's locked Datenbereich picker: a definition's subject is
            // what one row of its file *is*, so a different subject is a different export.
            if (definition.subjectKey != subject.key) {
                reject("The subject of a saved export cannot be changed; save a new export for '${subject.key}' instead")
            }
            definition.writeHeader(request, subject, dateFrom, dateTo)
            definition.updatedAt = currentTimeStamp()
            ExportDefinitionFieldTable.deleteWhere { ExportDefinitionFieldTable.definitionId eq definition.id }
            writeFields(definition, request.fields)
            definition.toDto()
        }
    }

    /**
     * Everything [save] and [update] refuse before touching a table, returning the resolved subject.
     *
     * One function for both writes, so an edit is checked against the same catalogue, the same
     * capability requirements, the same aggregate cap and the same suppression rule as a new save.
     */
    @OptIn(TestOnlyApi::class)
    private fun validated(
        request: CustomExportDto.SaveDefinitionRequestDto,
        held: Set<Capability>,
    ): ExportSubject {
        if (request.name.trim().isEmpty()) reject("An export definition needs a name")
        // An export of no columns is a mistake, not a request: it would produce a file of empty rows.
        if (request.fields.isEmpty()) reject("An export definition needs at least one field")

        val subject =
            ExportCatalogue.subject(request.subjectKey)
                ?: reject("Unknown export subject '${request.subjectKey}'")
        // Before `requireUsable`, so a suppressed aggregate is refused with the reason rather than as
        // an unknown key: the catalogue no longer offers it, so the generic refusal would fire first
        // and tell the operator nothing about why no export summarises figures per person.
        suppressedAggregateAmong(subject, request.fields.map { it.fieldKey })?.let { rejectSuppressed(subject, it, ::reject) }
        request.fields.forEach { requireUsable(it.fieldKey, subject, held) }
        // Refused here, not only in `run`. A definition the runner will reject is worse than a
        // rejected save: the operator learns about it when they press *Herunterladen*, and the stored
        // definition is then permanently un-runnable until somebody edits it.
        //
        // Every key resolved above, so `mapNotNull` drops nothing — an unresolvable key already left
        // through `requireUsable`.
        requireWithinAggregateCap(
            request.fields.mapNotNull { ExportFieldResolver.fieldFor(it.fieldKey, subject.allOfferings) },
        )
        requireDistinctPositions(request.fields)
        request.enabledToggles.forEach { key ->
            if (subject.toggles.none { it.key == key }) {
                reject("Unknown toggle '$key' for subject '${subject.key}'")
            }
        }
        requireDateFilterApplies(subject, request.dateFrom, request.dateTo)
        return subject
    }

    /** The definition's own columns, shared by [save] and [update]. Must run inside a transaction. */
    private fun ExportDefinition.writeHeader(
        request: CustomExportDto.SaveDefinitionRequestDto,
        subject: ExportSubject,
        dateFrom: LocalDateTime?,
        dateTo: LocalDateTime?,
    ) {
        this.name = request.name.trim()
        this.subjectKey = subject.key
        this.dateFrom = dateFrom
        this.dateTo = dateTo
        // Null rather than "[]" for the empty case, so "no toggle was ever switched on"
        // and "the empty set was stored" do not become two shapes meaning one thing.
        this.enabledToggles =
            request.enabledToggles.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
    }

    /** Inserts [fields] as [definition]'s columns. Must run inside a transaction. */
    private fun writeFields(
        definition: ExportDefinition,
        fields: List<CustomExportDto.DefinitionFieldDto>,
    ) {
        fields.forEach { field ->
            ExportDefinitionField.new {
                this.definition = definition
                this.fieldKey = field.fieldKey
                this.position = field.position
                this.heading = field.heading
            }
        }
    }

    override suspend fun delete(id: Long) {
        dbQuery {
            val definition = ExportDefinition.findById(id) ?: throw definitionNotFound(id)
            // The child rows go with it through the table's CASCADE, not by hand.
            definition.delete()
        }
    }

    /**
     * See [CustomExportService.run].
     *
     * Deliberately a short orchestration of four named steps — resolve, filter, fetch, write. Every
     * rule this feature has converges on this one call, and a single long method is how one of them
     * ends up silently missing.
     */
    @OptIn(TestOnlyApi::class)
    override suspend fun run(
        definitionId: Long,
        held: Set<Capability>,
    ): CustomExportDto.RunResultDto =
        dbQuery {
            val definition =
                ExportDefinition.findById(definitionId)
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Export definition $definitionId not found",
                    )
            val subject = resolveSubject(definition)
            val columns = resolveColumns(definition, subject, held)

            val fetched =
                sourceFor(subject, columns)
                    .select(columns.map { it.expression }.distinct())
                    .where(rowFilter(definition, subject))
                    // Ordered before the cap, so the rows kept are the earliest, and every run of an
                    // unchanged definition yields the same file.
                    .orderBy(*rowOrder(subject))
                    // One more than we will keep: that single extra row is how we know the query
                    // matched more than the cap without paying for a second COUNT over the same
                    // predicate.
                    .limit(rowCap + 1)
                    .toList()
            val truncated = fetched.size > rowCap
            val kept = if (truncated) fetched.take(rowCap) else fetched

            CustomExportDto.RunResultDto(
                fileName = fileNameFor(definition.name),
                bytes = writeCsv(columns, kept),
                rowCount = kept.size,
                truncated = truncated,
            )
        }

    /**
     * One column of the output: which catalogue offering feeds it, as what SQL, under which heading.
     *
     * The triple is resolved once, in `position` order, and then carries that order through both the
     * heading row and every data row — so the two cannot drift apart.
     *
     * [expression] is built here and **reused by identity**: it is what goes into the `SELECT` list and
     * what each row is read back by, and Exposed matches a result row's values to the very expression
     * instances the query was built from. A second, equal-looking expression built at write time would
     * not be found in the row at all.
     */
    private data class ResolvedColumn(
        val offering: ExportOffering,
        val expression: Expression<*>,
        val heading: String,
    )

    /**
     * The SQL one offering contributes: a column, or a correlated subquery.
     *
     * The `when` is exhaustive over the sealed [ExportOffering], which is what stops a future third
     * kind from being silently dropped from every export that ticks it.
     */
    private fun expressionFor(
        offering: ExportOffering,
        definition: ExportDefinition,
    ): Expression<*> =
        when (offering) {
            is ExportField -> offering.column
            // The definition's own Zeitraum, handed down so a period-scoped aggregate can bound its
            // subquery with it. The outer query's date filter is a different predicate on a different
            // table's column; see `AggregateExpressions.scopedWhere`.
            is ExportAggregateField -> AggregateExpressions.expressionFor(offering, definition.dateFrom, definition.dateTo)
        }

    /**
     * The subject the definition names, or a refusal.
     *
     * Brokenness is reported on read ([list]/[get]) and refused on run. There is nothing sensible to
     * produce from a definition whose subject the catalogue lost: not an empty file, not a partial
     * one.
     */
    private fun resolveSubject(definition: ExportDefinition): ExportSubject =
        ExportCatalogue.subject(definition.subjectKey)
            ?: rejectBroken("Export definition ${definition.id.value} names an unknown subject '${definition.subjectKey}'")

    /**
     * Every stored field key resolved against today's catalogue and checked against [held], in
     * `position` order.
     *
     * Two refusals, and neither degrades into a blank column:
     *
     * - A key the catalogue no longer knows makes the definition broken. Running the rest would hand
     *   the operator a file that is quietly one column short — the failure this feature exists to
     *   avoid, and one nobody notices until a reconciliation disagrees.
     * - A field [held] does not cover — including any capability on the *path* it is reached
     *   through, not only its own table's — is refused **naming the capability**, because the runner is not
     *   necessarily the author: a definition is shared and outlives whoever built it, so save-time
     *   validation says nothing about who is running it today.
     */
    private fun resolveColumns(
        definition: ExportDefinition,
        subject: ExportSubject,
        held: Set<Capability>,
    ): List<ResolvedColumn> {
        val stored =
            ExportDefinitionField
                .find { ExportDefinitionFieldTable.definitionId eq definition.id }
                .orderBy(ExportDefinitionFieldTable.position to SortOrder.ASC)
                .toList()
        if (stored.isEmpty()) rejectBroken("Export definition ${definition.id.value} has no columns")

        // `allOfferings`, not `allFields`: a stored aggregate key resolves against the same list its
        // column siblings do, or every saved aggregate would be reported as a key the catalogue no
        // longer knows — a definition refused as broken the moment it was saved.
        //
        // Bound once: it walks the schema graph on every read, and the loops below would otherwise
        // re-walk it per stored key.
        val offered = subject.allOfferings
        // The run-time half of the aggregate suppression, and the reason it is here rather than only
        // in [save]: a definition saved before a subject stopped offering aggregates — or written by
        // hand against the table — must not run. Checked before the unknown-key refusal below, which
        // would otherwise swallow it and report a privacy decision as a broken definition.
        suppressedAggregateAmong(subject, stored.map { it.fieldKey })?.let { rejectSuppressed(subject, it, ::rejectBroken) }
        // Resolved through [ExportFieldResolver], so a key stored before a column was renamed still
        // finds its field — the same resolution the save path and the brokenness check use.
        val resolved = stored.map { row -> row to ExportFieldResolver.fieldFor(row.fieldKey, offered) }
        val unknown = resolved.filter { (_, field) -> field == null }.map { (row, _) -> row.fieldKey }
        if (unknown.isNotEmpty()) {
            rejectBroken(
                "Export definition ${definition.id.value} names fields the catalogue no longer knows: " +
                    unknown.joinToString(),
            )
        }

        val columns =
            resolved.mapNotNull { (row, offering) ->
                offering?.let { ResolvedColumn(it, expressionFor(it, definition), row.heading) }
            }
        // The path's whole required set, not the field's own capability: a relation field is only
        // usable by someone who may read every table the query touches to reach it.
        //
        // Keyed by the **resolved** field's current key, never by the stored one: the map holds no
        // legacy keys, so looking one up there would find nothing and `orEmpty()` would then wave a
        // legacy-keyed field straight past its capability check.
        val requirements = subject.capabilityRequirements()
        val refused =
            columns.firstNotNullOfOrNull { column ->
                requirements[column.offering.key]
                    .orEmpty()
                    .firstOrNull { it !in held }
                    ?.let { column.offering.key to it }
            }
        if (refused != null) {
            throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "Field '${refused.first}' requires ${refused.second}",
            )
        }
        requireWithinAggregateCap(columns.map { it.offering })
        return columns
    }

    /**
     * The first key among [fieldKeys] that names an aggregate this subject declines to offer, or null.
     *
     * One finder, read by [save] and by [resolveColumns], so the two paths cannot disagree about the
     * same definition — the same reasoning [requireWithinAggregateCap] carries. They differ only in
     * how they refuse: a save is a bad *request* (400), a stored definition that can no longer run is
     * a *broken* one (409), which is what the operator's list already shows brokenness as.
     *
     * Exact key match, with no legacy leg: a legacy key belongs to a renamed database column and
     * `ExportFieldResolver` deliberately does not carry one for aggregates.
     *
     * Asks the subject rather than testing [ExportSubject.offersAggregates] first. There are two
     * suppression rules — the per-subject flag and the per-path monitoring rule — and a shortcut on
     * one of them would wave every key suppressed by the *other* straight through to the unknown-field
     * refusal, which is the wrong sentence and, at save time, no refusal at all.
     * [ExportSubject.suppressedAggregates] is the union of both, stated in one place.
     */
    private fun suppressedAggregateAmong(
        subject: ExportSubject,
        fieldKeys: List<String>,
    ): String? {
        val suppressed = subject.suppressedAggregates.map { it.key }.toSet()
        if (suppressed.isEmpty()) return null
        return fieldKeys.firstOrNull { it in suppressed }
    }

    /**
     * Refuses [fieldKey] as a suppressed aggregate, in the operator's terms rather than the graph's.
     *
     * The sentence names the reason, because "unknown field" would send somebody looking for a bug
     * that is not there: the column is derivable, it is cheap, and it is withheld on purpose.
     */
    private fun rejectSuppressed(
        subject: ExportSubject,
        fieldKey: String,
        reject: (String) -> Nothing,
    ): Nothing =
        reject(
            "'$fieldKey' is a summary figure about a member of staff. The subject '${subject.label}' " +
                "offers no such column, and no other subject does either: per-head performance " +
                "figures are Leistungsdaten and no export summarises figures per person.",
        )

    /**
     * Bounds the per-row cost of one run; see [maxAggregateColumns].
     *
     * Counted over **resolved offerings** rather than the stored or requested keys, so the number in
     * the refusal is the number of subqueries the run would actually have emitted — and so a key
     * stored under a legacy spelling is counted exactly once, as the field it resolves to.
     *
     * One function, two call sites — [save] and [resolveColumns]. A second copy is how a save path
     * and a run path end up disagreeing about the same definition, which is precisely the defect this
     * guard's save-time leg was added to close.
     */
    private fun requireWithinAggregateCap(offerings: List<ExportOffering>) {
        val aggregates = offerings.count { it is ExportAggregateField }
        if (aggregates > maxAggregateColumns) {
            reject(
                "This export selects $aggregates aggregate columns, more than the limit of " +
                    "$maxAggregateColumns. Each one is a separate subquery per exported row. " +
                    "Remove some.",
            )
        }
    }

    /**
     * The query source: the subject's root, plus the relation paths this run actually needs.
     *
     * Two reasons a path is needed — a selected column lives on it, or the subject declared it
     * [ExportSubject.alwaysJoined] because a filter reads it. Nothing else is joined: a table nobody
     * asked about costs a LEFT JOIN on every row for no column in the file.
     *
     * Matched on the **field key**, not on the column's table: a table reachable two ways contributes
     * two distinct sets of fields, and only the key says which route a stored definition meant.
     *
     * **Aggregate keys are partitioned off before this, not filtered inside it.** An aggregate's path
     * is to-many, and joining a to-many path is the row multiplication the whole aggregate design
     * exists to prevent — so such a path must never reach `ExportRelationPath.applyAll` or the
     * two-routes check. Its hops belong inside the correlated subquery
     * [AggregateExpressions.expressionFor] builds, and nowhere else in the query.
     */
    @OptIn(TestOnlyApi::class)
    private fun sourceFor(
        subject: ExportSubject,
        columns: List<ResolvedColumn>,
    ): ColumnSet = joinSourceFor(subject, columns.mapNotNull { (it.offering as? ExportField)?.key })

    /**
     * [sourceFor] over bare field keys, and the one place the three join guards are applied.
     *
     * `internal` so a test can drive a refusal without assembling resolved columns — and, for the
     * to-many guard, without a to-many field the catalogue will not offer in the first place.
     *
     * The guards are checked before anything is folded, so the operator gets a sentence naming the
     * problem rather than invalid SQL or a quietly wrong cell.
     */
    @TestOnlyApi
    fun joinSourceFor(
        subject: ExportSubject,
        selectedFieldKeys: List<String>,
    ): ColumnSet {
        val selected =
            subject.paths.filter { path ->
                selectedFieldKeys.any { key -> path.fields.any { it.key == key } }
            }
        val needed = subject.alwaysJoined + selected
        requireNoToManyPath(needed)
        requireOneRoutePerTable(needed)
        requireWithinJoinCap(needed)
        return ExportRelationPath.applyAll(subject.root, needed)
    }

    /**
     * The second lock on to-many, the first being that [ExportSubject.paths] never offers one.
     *
     * A to-many path joined naively multiplies rows: three partners holding four IBANs between them
     * come out as a seven-row "partner list", every other column duplicated, and nothing in the file
     * says so. The recipient sees a plausible CSV and reconciles against it. To-many belongs to a
     * later slice as a correlated subquery, which leaves the row count untouched.
     *
     * Worth a second lock because [ExportSubject.alwaysJoined] bypasses the first: it is taken
     * straight from `SchemaGraph.pathsFrom`, which returns every path including the to-many ones, so
     * a subject author is one missing filter away from multiplying every run of that subject.
     */
    private fun requireNoToManyPath(paths: List<ExportRelationPath>) {
        val toMany = paths.firstOrNull { it.cardinality == Cardinality.MANY } ?: return
        rejectBroken(
            "Relation '${toMany.key}' can match several rows per record, so joining it would multiply " +
                "the exported rows. It must be aggregated, not joined.",
        )
    }

    /**
     * Refuses two paths that would reach one table by different foreign keys.
     *
     * One FROM clause holds the table once, so [ExportRelationPath.applyAll] would keep one route and
     * drop the other — and the dropped path's columns would then read the surviving route's row. A
     * wrong value in a cell is worse than a refusal, because nothing about the file says it happened.
     *
     * No registered subject can produce this pair today; the check is what keeps that from becoming a
     * silent wrong answer the day one does. The real fix is a table alias per visit, threaded through
     * the join, the column references and the stored key scheme — deliberately out of scope.
     */
    private fun requireOneRoutePerTable(paths: List<ExportRelationPath>) {
        val conflict = ExportRelationPath.tableJoinedTwoWays(paths) ?: return
        rejectBroken(
            "Two relation paths reach '${conflict.tableName}' by different foreign keys. One export " +
                "cannot join a table twice, so this definition cannot be run.",
        )
    }

    /** Bounds the SQL one click can produce; see [maxJoinedTables]. */
    private fun requireWithinJoinCap(paths: List<ExportRelationPath>) {
        val tables = paths.flatMap { path -> path.hops.map { it.target } }.distinct()
        if (tables.size > maxJoinedTables) {
            reject(
                "This export would join ${tables.size} related tables, more than the limit of " +
                    "$maxJoinedTables. Remove some columns.",
            )
        }
    }

    /**
     * Everything narrowing the rows, ANDed.
     *
     * The shop constraint leads, and is not conditional on anything the operator chose. A subject
     * declaring a [ExportSubject.shopColumn] is always narrowed to the current shop: without it a
     * partner export emits every shop's rows, which looks identical in a single-shop install and is
     * a data leak the moment it is not one.
     *
     * [Op.TRUE] is the identity for the fold, and is what a subject with no shop column, no base
     * predicate, no bounds and no toggles legitimately reduces to.
     */
    private fun rowFilter(
        definition: ExportDefinition,
        subject: ExportSubject,
    ): Op<Boolean> =
        buildList {
            subject.shopColumn?.let { add(it eq currentShop().id) }
            subject.basePredicate?.let { add(it()) }
            addAll(dateBounds(definition, subject.dateColumn))
            addAll(togglePredicates(definition, subject))
        }.fold<Op<Boolean>, Op<Boolean>>(Op.TRUE) { acc, op -> acc and op }

    /**
     * The order an export's rows come out in: by the subject's [date column][ExportSubject.dateColumn]
     * ascending — the column the Zeitraum filters, so the file reads chronologically — then by the
     * root's id as a tiebreak, so rows sharing a timestamp (or a subject with no date at all) still
     * come out in one stable order. Without an `ORDER BY` the database returns whatever its plan
     * yields, which differs between runs and is why the QA pass found the rows "not ordered by date".
     */
    internal fun rowOrder(subject: ExportSubject): Array<Pair<Expression<*>, SortOrder>> =
        buildList<Pair<Expression<*>, SortOrder>> {
            subject.dateColumn?.let { add(it to SortOrder.ASC) }
            (subject.root as? IdTable<*>)?.let { add(it.id to SortOrder.ASC) }
        }.toTypedArray()

    /**
     * The date range, for whichever bounds are set.
     *
     * Inclusive at both ends: an operator asking for "bis 31.12." means the 31st is in. Silently
     * dropped when the subject has no date column — [requireDateFilterApplies] already refused
     * storing bounds against such a subject, so reaching here means the catalogue changed under a
     * definition saved when it did have one.
     */
    private fun dateBounds(
        definition: ExportDefinition,
        dateColumn: Column<LocalDateTime>?,
    ): List<Op<Boolean>> {
        if (dateColumn == null) return emptyList()
        return buildList {
            definition.dateFrom?.let { add(dateColumn greaterEq it) }
            definition.dateTo?.let { add(dateColumn lessEq it) }
        }
    }

    /**
     * The predicate behind each toggle the operator switched on.
     *
     * A stored toggle key the catalogue no longer knows is refused, not skipped. Skipping it would
     * widen the export — "Nur aktive" quietly becoming "alle" is precisely the kind of change an
     * operator cannot see in the file they receive.
     */
    private fun togglePredicates(
        definition: ExportDefinition,
        subject: ExportSubject,
    ): List<Op<Boolean>> {
        val enabled = definition.enabledToggles?.let { Json.decodeFromString<List<String>>(it) } ?: emptyList()
        return enabled.map { key ->
            val toggle =
                subject.toggles.firstOrNull { it.key == key }
                    ?: rejectBroken(
                        "Export definition ${definition.id.value} enables a filter the catalogue no longer knows: '$key'",
                    )
            toggle.predicate()
        }
    }

    /** The heading row, then one row per record, in the same column order by construction. */
    @OptIn(TestOnlyApi::class)
    private fun writeCsv(
        columns: List<ResolvedColumn>,
        rows: List<ResultRow>,
    ): ByteArray {
        val writer = SpreadsheetCsvWriter()
        // Headings are [CsvValue.Text] and therefore formula-guarded: an operator typed them, and
        // operator-entered text is exactly what the guard is for, heading row or not.
        writer.writeRow(columns.map { CsvValue.Text(it.heading) })
        rows.forEach { row ->
            // Read back by the very expression the SELECT was built from — a column or a subquery,
            // rendered by its declared type either way. An aggregate that matched no related row
            // arrives as SQL left it: `0` from a `count(*)`, NULL — and so a blank cell — from every
            // other reduction, which is the distinction between "no invoices" and "invoices totalling
            // nothing" and must not be coalesced away.
            writer.writeRow(columns.map { renderCell(it.offering.type, row.getOrNull(it.expression)) })
        }
        return writer.toByteArray()
    }

    /**
     * One database value as one CSV cell, decided by the field's declared type.
     *
     * `internal` so the money case can be proven directly: no catalogue subject carries a
     * [ExportFieldType.MONEY] field yet, and the rule it encodes — a negative amount must reach
     * Excel unguarded, or the column will not sum — is worth a test before the first one arrives.
     *
     * Null becomes an empty cell, never the string "null".
     */
    @TestOnlyApi
    fun renderCell(
        type: ExportFieldType,
        value: Any?,
    ): CsvValue {
        if (value == null) return CsvValue.Text("")
        return when (type) {
            ExportFieldType.TEXT -> CsvValue.Text(value.toString())
            ExportFieldType.DATE -> CsvValue.Text(renderDate(value))
            ExportFieldType.BOOLEAN -> CsvValue.Text(if (value == true) "Ja" else "Nein")
            // Numeric, so the formula guard is skipped — see CsvValue.Numeric.
            ExportFieldType.MONEY -> CsvValue.Numeric(renderMoney(value))
            ExportFieldType.NUMBER -> CsvValue.Numeric(renderNumber(value))
        }
    }

    /**
     * ISO `yyyy-MM-dd` — the date only, whatever precision the column carries.
     *
     * A time of day in a spreadsheet column of dates is noise the recipient then has to strip. Both
     * kotlinx and `java.time` date-times print as `2026-09-01T10:15`, so cutting at the `T` is the
     * one rule that covers every date-ish column a subject can declare.
     */
    private fun renderDate(value: Any): String =
        when (value) {
            is LocalDateTime -> value.date.toString()
            is LocalDate -> value.toString()
            else -> value.toString().substringBefore('T')
        }

    /**
     * An amount, scale 2, with a **decimal comma**.
     *
     * German Excel reads `1234,56` as a number and `1234.56` as text — a column of amounts that will
     * not sum is a defect the recipient discovers weeks later, in their own totals.
     *
     * A `Double` is refused rather than converted. Money never travels through binary floating point
     * in this codebase (see `platform/money/README.md`), and a `MONEY` field declared over a float column is a
     * catalogue mistake to be fixed there, not rounded over here.
     */
    private fun renderMoney(value: Any): String {
        val amount =
            value as? BigDecimal
                ?: error("A MONEY export field must read a decimal column, but got ${value::class.simpleName}")
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
    }

    /**
     * A non-money number, with the same decimal comma and its own scale left alone — a quantity of
     * `2.5` is not `2,50`.
     *
     * A `Double` goes through `BigDecimal`'s **string** constructor, never `BigDecimal(double)`,
     * which would render 0.1 as 0.1000000000000000055511151231257827.
     */
    private fun renderNumber(value: Any): String =
        when (value) {
            is BigDecimal -> value.toPlainString().replace('.', ',')
            is Double, is Float -> BigDecimal(value.toString()).toPlainString().replace('.', ',')
            else -> value.toString()
        }

    /**
     * A file name derived from the definition's own name, so an operator running three exports can
     * tell the downloads apart.
     *
     * Sanitising, in order: German umlauts transliterated (`ä` → `ae`, `ß` → `ss`) so they survive
     * as letters rather than as underscores; every remaining non-ASCII-alphanumeric character
     * replaced with `_`; runs of `_` collapsed and trimmed; capped at 60 characters; an empty result
     * falling back to `Export`.
     *
     * The last rule is not cosmetic: the name **must not begin `EXTF`**. That prefix is DATEV's, and
     * every DATEV export this system writes carries it. A custom extract an operator assembled is not
     * a DATEV file, and a file name is all the tax advisor's importer has to tell them apart.
     */
    @TestOnlyApi
    fun fileNameFor(name: String): String {
        val transliterated =
            name
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("Ä", "Ae")
                .replace("Ö", "Oe")
                .replace("Ü", "Ue")
                .replace("ß", "ss")
        val ascii = transliterated.map { if (it.code < 128 && it.isLetterOrDigit()) it else '_' }.joinToString("")
        val base =
            ascii
                .replace(Regex("_+"), "_")
                .trim('_')
                .take(60)
                .trim('_')
                .ifEmpty { "Export" }
        val guarded = if (base.uppercase().startsWith("EXTF")) "Export_$base" else base
        return "$guarded.csv"
    }

    /**
     * A definition today's catalogue cannot run.
     *
     * Its own status, not [PosErrorCode.INVALID_REQUEST]: the request was fine, the stored definition
     * is not, and the operator's fix is to edit or delete it rather than to resend anything.
     */
    private fun rejectBroken(message: String): Nothing = throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.Conflict, message)

    /**
     * The server-side half of the field guard.
     *
     * Checks the field's whole requirement — every table the query would touch to reach it — through
     * [ExportSubject.capabilityRequirements], the same lookup [resolveColumns] reads.
     *
     * A well-behaved client only offers what [catalogue] returned, so neither of these rejections
     * should ever be seen by an honest caller. That is exactly why they are here: the client's
     * filtering decides what is *offered*, and only this decides what is *accepted*.
     */
    private fun requireUsable(
        fieldKey: String,
        subject: ExportSubject,
        held: Set<Capability>,
    ) {
        // Same resolution and same lookup the run path uses, so save-time and run-time cannot
        // disagree about a field — including one named by its legacy key.
        val required =
            ExportFieldResolver.requirementsFor(fieldKey, subject.allOfferings, subject.capabilityRequirements())
                ?: reject("Unknown field '$fieldKey' for subject '${subject.key}'")
        val missing = required.firstOrNull { it !in held }
        if (missing != null) {
            reject("Field '$fieldKey' requires $missing")
        }
    }

    /**
     * Two columns may not claim the same slot.
     *
     * A definition is saved so the same extract comes out the same way next month; that is the whole
     * reason it exists rather than being re-picked each time. Two fields at one position leave the
     * column order to whatever the database returns, which is the defect
     * `ExportDefinition.fields`' KDoc warns about — only arriving from the client instead of from the
     * DAO, so sorting on read cannot save us.
     *
     * Distinct, not contiguous and not zero-based: a client sending 0/2/5 has expressed an
     * unambiguous order, and there is nothing wrong with it to refuse.
     */
    private fun requireDistinctPositions(fields: List<CustomExportDto.DefinitionFieldDto>) {
        val duplicates =
            fields
                .groupBy { it.position }
                .filterValues { it.size > 1 }
                .keys
                .sorted()
        if (duplicates.isNotEmpty()) {
            reject("Two fields share a position: ${duplicates.joinToString()}")
        }
    }

    /**
     * A date range is only meaningful against a subject that has a date to range over.
     *
     * Storing one on a subject with no `dateColumn` would not merely do nothing — it would render in
     * the definition and read to the operator as though the export *were* date-filtered, which is the
     * failure mode this feature is trying to remove rather than introduce.
     *
     * `internal` rather than private so the test can reach it: every subject in the catalogue today
     * has a date column, so there is no request that can drive this branch through [save], and a
     * guard that only exists for the second subject is exactly the one worth proving before it
     * arrives.
     */
    @TestOnlyApi
    fun requireDateFilterApplies(
        subject: ExportSubject,
        dateFrom: String?,
        dateTo: String?,
    ) {
        val bounded = !dateFrom.isNullOrBlank() || !dateTo.isNullOrBlank()
        if (bounded && subject.dateColumn == null) {
            reject("Subject '${subject.key}' has no date column, so a date range cannot apply to it")
        }
    }

    /**
     * The shop a new definition is filed under.
     *
     * Single-shop assumption, stated rather than hidden, and taken from the same place
     * `SupplierRepositoryImpl.create` takes it. Multi-shop work must pass a shop in explicitly:
     * an arbitrary `first()` over several shops would file a definition under the wrong one.
     */
    private fun currentShop(): ShopInfo =
        ShopInfo.all().firstOrNull()
            ?: throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "No shop is configured, so an export definition has nothing to belong to",
            )

    /** ISO-8601 in, `LocalDateTime` out. A bound we cannot read is a bad request, not a null bound. */
    private fun parseBound(
        raw: String?,
        field: String,
    ): LocalDateTime? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { LocalDateTime.parse(value) }
            .getOrElse { reject("$field is not an ISO-8601 date-time: '$value'") }
    }

    private fun definitionNotFound(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Export definition $id not found",
        )

    /** Nothing after this returns, which is what lets the call sites read as expressions. */
    private fun reject(message: String): Nothing = throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, message)

    /**
     * Reads the definition and decides, against today's catalogue, whether it can still be run.
     *
     * Field keys are only checked when the subject itself resolved: if the subject is gone there is
     * nothing to check them against, and listing every field key as "unknown" would be noise on top
     * of the one fact that matters.
     *
     * Must run inside a transaction — the fields are read lazily.
     */
    private fun ExportDefinition.toDto(): CustomExportDto.DefinitionDto {
        val subject = ExportCatalogue.subject(subjectKey)
        // Explicit orderBy: DAO referrers carry no ordering, and the column order is the export.
        val storedFields =
            ExportDefinitionField
                .find { ExportDefinitionFieldTable.definitionId eq id }
                .orderBy(ExportDefinitionFieldTable.position to SortOrder.ASC)
                .toList()
        val unknownKeys =
            buildList {
                if (subject == null) {
                    add(subjectKey)
                } else {
                    // `allOfferings`: a stored aggregate is a perfectly runnable definition, and
                    // reporting it as broken in the operator's list is a lie they would act on by
                    // deleting an export that works.
                    val offered = subject.allOfferings
                    storedFields
                        // Resolved, not compared: a definition on a legacy key runs perfectly, and
                        // reporting it as broken in the operator's list would be a lie they act on.
                        .filter { stored -> ExportFieldResolver.fieldFor(stored.fieldKey, offered) == null }
                        .forEach { add(it.fieldKey) }
                }
            }
        return CustomExportDto.DefinitionDto(
            id = id.value,
            name = name,
            subjectKey = subjectKey,
            fields =
                storedFields.map {
                    CustomExportDto.DefinitionFieldDto(
                        fieldKey = it.fieldKey,
                        position = it.position,
                        heading = it.heading,
                    )
                },
            dateFrom = dateFrom?.toString(),
            dateTo = dateTo?.toString(),
            enabledToggles = enabledToggles?.let { Json.decodeFromString<List<String>>(it) } ?: emptyList(),
            isBroken = unknownKeys.isNotEmpty(),
            unknownKeys = unknownKeys,
        )
    }

    companion object {
        /**
         * Production's row cap.
         *
         * A hundred thousand rows of a wide subject is already a file no spreadsheet opens
         * comfortably; well past it we are only choosing how the server runs out of heap.
         */
        const val DEFAULT_ROW_CAP = 100_000

        /**
         * Production's join cap.
         *
         * Comfortably above anything today's two subjects can reach at depth 2, and low enough that a
         * registry grown to dozens of tables cannot quietly produce a join nobody has ever read.
         */
        const val DEFAULT_MAX_JOINED_TABLES = 8

        /**
         * Production's aggregate-column cap.
         *
         * Five correlated subqueries against [DEFAULT_ROW_CAP] rows is already half a million of them;
         * a creditor overview wanting more than five aggregated figures is asking for a report, not a
         * column list.
         */
        const val DEFAULT_MAX_AGGREGATE_COLUMNS = 5
    }
}
