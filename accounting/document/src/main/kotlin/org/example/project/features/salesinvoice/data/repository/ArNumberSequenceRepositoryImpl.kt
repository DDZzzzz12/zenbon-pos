package org.example.project.features.salesinvoice.data.repository

import org.example.project.features.salesinvoice.database.tables.ArNumberSequenceTable
import org.example.project.features.salesinvoice.domain.repository.ArNumberSequenceRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Exposed implementation of [ArNumberSequenceRepository]. Requires an ambient transaction.
 *
 * The `forUpdate()` is the whole mechanism: it serialises two concurrent issuances on the counter
 * row so they cannot read the same value. Without it both would read `n` and write `n + 1`, and two
 * invoices would carry the same §14 number.
 *
 * A year's row is created by its first issuance, and two first issuances of a year used to race:
 * neither found a row to lock, both INSERTed, and the loser died on
 * `ar_number_sequence_prefix_year_unique` with a 500. They now meet in `INSERT … ON CONFLICT DO
 * NOTHING` (`insertIgnore`): the loser waits for the winner's insert to commit, inserts nothing,
 * and then locks and reads the winner's row like any later issuance. Under READ COMMITTED (the
 * default `dbQuery` isolation) that is race-free; under REPEATABLE_READ the loser gets a
 * serialization failure instead, which the service maps to 409 `SALES_INVOICE_NUMBER_COLLISION`.
 */
class ArNumberSequenceRepositoryImpl : ArNumberSequenceRepository {
    override fun nextNumber(
        prefix: String,
        year: Int,
        startAt: Long,
        padding: Int,
    ): String {
        require(prefix.isNotBlank()) { "AR number prefix must not be blank" }
        require(startAt >= 1L) { "AR number startAt must be at least 1, was $startAt" }

        val issued =
            lockedNextValue(prefix, year) ?: run {
                ArNumberSequenceTable.insertIgnore {
                    it[ArNumberSequenceTable.prefix] = prefix
                    it[ArNumberSequenceTable.year] = year
                    it[nextValue] = startAt
                }
                checkNotNull(lockedNextValue(prefix, year)) { "ar_number_sequence row for $prefix/$year vanished after insert" }
            }
        ArNumberSequenceTable.update(
            { (ArNumberSequenceTable.prefix eq prefix) and (ArNumberSequenceTable.year eq year) },
        ) {
            it[nextValue] = issued + 1
        }

        return "$prefix-$year-${issued.toString().padStart(padding, '0')}"
    }

    /** The year's next value, row-locked until the transaction ends; null before its first issuance. */
    private fun lockedNextValue(
        prefix: String,
        year: Int,
    ): Long? =
        ArNumberSequenceTable
            .selectAll()
            .where { (ArNumberSequenceTable.prefix eq prefix) and (ArNumberSequenceTable.year eq year) }
            .forUpdate()
            .singleOrNull()
            ?.get(ArNumberSequenceTable.nextValue)
}
