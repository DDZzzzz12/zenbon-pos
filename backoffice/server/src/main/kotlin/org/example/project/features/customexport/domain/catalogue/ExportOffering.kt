package org.example.project.features.customexport.domain.catalogue

import org.example.project.core.domain.enums.auth.Capability

/**
 * Anything an operator may tick: a column of a joinable table, or an aggregate over a to-many one.
 *
 * The two kinds stay separate types — a column names a [ExportField.column], an aggregate names a
 * [ExportAggregateField.table] and a subquery — and they are consumed differently. What they share is
 * everything the *picker* and the *capability check* need, which is exactly what is declared here.
 *
 * **Sealed on purpose.** A subject could as easily have handed out two lists, one per kind, and the
 * runner that then selected the columns and never read the second list would compile, run, and quietly
 * drop every aggregate an operator ticked. One list of a sealed type makes that failure impossible to
 * write: a `when` over an offering is exhaustive, so a branch for [ExportField] without one for
 * [ExportAggregateField] is a compile error rather than a column missing from a file. The path keeps
 * its two lists (`fields`/`aggregateFields`) because there the two kinds are *built* differently; the
 * subject offers one list because there they are *chosen* together.
 *
 * @property key what a stored definition records. The two kinds' namespaces are disjoint by
 *   construction — an aggregate key carries `~`, which cannot appear in a table or column name — so
 *   one map keyed by this can hold both without a collision, and the save check and the run check can
 *   never resolve one stored key two ways.
 */
sealed interface ExportOffering {
    val key: String
    val label: String
    val type: ExportFieldType
    val capability: Capability

    /** The picker's section heading, or null for a column of the subject's own table. */
    val group: String?
}
