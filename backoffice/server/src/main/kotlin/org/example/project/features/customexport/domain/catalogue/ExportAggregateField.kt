package org.example.project.features.customexport.domain.catalogue

import org.example.project.core.domain.enums.auth.Capability
import org.jetbrains.exposed.v1.core.Table

/**
 * One aggregate column an operator may tick, attached to the to-many path it is reached through.
 *
 * Deliberately **not** an [ExportField] — a sibling under [ExportOffering], never a subtype of it.
 * That type's `column` is non-null so the denylist check can be total: every offered column field
 * names a column, so every one of them can be proven not to reach denied data. An aggregate is a computed expression with no single column at all: a `COUNT` has
 * none. Widening [ExportField.column] to nullable to admit one would weaken that guarantee for
 * *every* field and leave the denylist check partial. Carrying [table] instead keeps the check total
 * and in fact stronger: an aggregate always has a table, even when it has no column.
 *
 * @property key what a stored definition records, shaped `<pathKey>~<aggKey>`, or
 *   `<pathKey>~<aggKey>@period` for the period twin. `~` cannot appear in a table or column name, so
 *   an aggregate key can never be read as a column field key — which is what lets both kinds share
 *   one `capabilityRequirements()` map, and therefore keeps the save check and the run check from
 *   disagreeing about the same definition.
 * @property capability what the viewer must hold to see this aggregate at all — the owning table's,
 *   exactly as its columns carry it. The path-level union is applied by the same machinery that
 *   applies it to column fields.
 * @property group the picker's section heading: the owning table's label, as for a column field.
 * @property table the table the aggregate's rows come from. Non-null, and the denylist check's hook.
 * @property path the to-many path the related rows are correlated through. Aggregates attach only to
 *   [Cardinality.MANY] paths: over a to-one path the row is already joinable, so a count of it is
 *   always 0 or 1 and a sum of it is the value itself.
 * @property periodScoped whether the export's Zeitraum constrains the aggregated rows. See
 *   [ExportAggregateField] and `SchemaGraph`'s twin rule for why both twins are offered.
 */
data class ExportAggregateField(
    override val key: String,
    override val label: String,
    override val type: ExportFieldType,
    override val capability: Capability,
    override val group: String?,
    val table: Table,
    val aggregate: ExportAggregate,
    val path: ExportRelationPath,
    val periodScoped: Boolean,
) : ExportOffering
