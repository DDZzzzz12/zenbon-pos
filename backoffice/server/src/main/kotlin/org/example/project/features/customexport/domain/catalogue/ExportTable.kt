package org.example.project.features.customexport.domain.catalogue

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.auth.Capability
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/**
 * One table an export may read, and everything about it a machine cannot work out.
 *
 * The capability sits here rather than on each column: it is one decision about one kind of data, and
 * repeating it per column only creates the chance of two columns of one table disagreeing.
 *
 * @property label the group heading the picker shows over this table's columns.
 * @property columns what an operator may tick when this table is reached by a to-one relation, or is
 *   the subject's own root.
 * @property aggregates what an operator may tick when this table is reached by a **to-many** relation,
 *   which cannot contribute ordinary columns without multiplying the export's rows. Declared here,
 *   next to the columns, so one table states everything about itself in one place.
 * @property periodColumn the column the export's Zeitraum applies to when this table is aggregated;
 *   null means this table's aggregates have no meaningful period and are offered once rather than
 *   twinned.
 */
data class ExportTable(
    val table: Table,
    val label: String,
    val capability: Capability,
    val columns: List<ExportColumn>,
    val aggregates: List<ExportAggregate> = emptyList(),
    val periodColumn: Column<LocalDateTime>? = null,
    /**
     * Whether a figure summarised **per row of this table** is performance monitoring.
     *
     * True on `employees` and nowhere else today. It is a statement about the table, not about any
     * one subject: whatever the export is rooted on, a path that travels through `employees` to reach
     * an aggregate groups that aggregate by a person, and the resulting column is a per-head figure —
     * Umsatz je Mitarbeiterin — however the file is titled.
     *
     * That is **Leistungs- und Verhaltenskontrolle** in the sense of § 87 Abs. 1 Nr. 6 BetrVG, whose
     * introduction the works council co-determines. So the catalogue withholds the aggregate on every
     * path that crosses such a table; see [ExportRelationPath.crossesMonitoringTable] for the walk and
     * [ExportSubject.offersAggregates] for how this per-path rule and the per-subject flag divide the
     * work between them.
     *
     * Declared here rather than as a table reference inside the walker so a future HR-ish table —
     * `employee_info`, a shift table, a time-tracking table — opts in by setting a flag next to its
     * columns, in the one place that already states everything else about it.
     *
     * **It suppresses summaries, not names.** This table's own [columns] are untouched: which member
     * of staff booked a receipt is operational fact, already on the screen the export mirrors. Only
     * the *derived per-head figure* goes.
     */
    val aggregatesAreMonitoring: Boolean = false,
) {
    init {
        // Same fail-fast reasoning as `ExportAggregate.init`: these are constants written in code, so
        // a bad entry should fail at class initialisation rather than on some later export run.
        val duplicate = aggregates.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            "Table '${table.tableName}' declares the aggregate key '${duplicate?.key}' twice; a stored " +
                "definition naming it could not say which one it meant."
        }

        // An aggregate reading another table's column would still compile and still produce SQL — a
        // correlated subquery over the wrong table, silently answering a question nobody asked.
        val foreign =
            aggregates.firstOrNull { aggregate ->
                listOfNotNull(aggregate.column, aggregate.orderColumn).any { it.table != table }
            }
        require(foreign == null) {
            "Aggregate '${foreign?.key}' on table '${table.tableName}' reads a column of another table; " +
                "its subquery would aggregate the wrong rows with nothing in the output to say so."
        }
    }
}
