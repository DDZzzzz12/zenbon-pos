package org.example.project.features.salesinvoice.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * The Ausgangsrechnung counter — one row per (prefix, year).
 *
 * A row rather than a `count()`, and a row rather than an in-process tracker, for two reasons that
 * both come from §14 UStG requiring a gapless, unique sequence:
 *
 * - **The lock crosses processes.** `BillNumberTracker`, which defends `bill_number`, is a JVM-local
 *   `Set` and would not survive a second server instance. `SELECT … FOR UPDATE` here does.
 * - **A failed issuance returns the number.** The increment commits with the invoice or rolls back
 *   with it, so a crash between "number handed out" and "invoice written" cannot leave a hole.
 *
 * [nextValue] is the number the *next* call will hand out, so a fresh row for a shop starting at 1
 * stores 1 and leaves storing 2 behind.
 */
object ArNumberSequenceTable : LongIdTable("ar_number_sequence", "id") {
    val prefix = varchar("prefix", 10)
    val year = integer("year")
    val nextValue = long("next_value")

    init {
        uniqueIndex(prefix, year)
    }
}
