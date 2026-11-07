package org.example.project.features.allocation.data.repository

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * The one definition of "overdue" for every due-date predicate (incoming and outgoing invoices,
 * the Rechnungen buckets, the row flag): an invoice is overdue only **after** its due day, i.e.
 * `due_date < start of today`. An invoice due today is still payable today and is not late at 00:00.
 *
 * Callers keep passing "now" as `overdueAsOf`; the repositories turn it into this cutoff, so a
 * reader of a call site cannot get the boundary wrong by passing the wrong instant.
 */
object OverdueCutoff {
    /** Midnight at the start of [asOf]'s day: a due date strictly before it is overdue. */
    fun of(asOf: LocalDateTime): LocalDateTime = LocalDateTime(asOf.date, LocalTime(0, 0))
}
