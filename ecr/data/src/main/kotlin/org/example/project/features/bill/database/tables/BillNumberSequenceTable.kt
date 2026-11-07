package org.example.project.features.bill.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * The Kassenbeleg counter — one row per month (`period` = `yyMM`), mirroring
 * [org.example.project.features.salesinvoice.database.tables.ArNumberSequenceTable].
 *
 * `bill_number` (`RE-yyMMdd-N`) used to take N from a `count(*)` of the month's bills. Two bills
 * created in parallel both counted the same rows and drew the same N; the in-process
 * `BillNumberTracker` could not stop it, because the reservation was released before the
 * transaction committed, and the loser died on `bill_bill_number_unique` with a 500 (real-TSE QA
 * rt12). The count also skipped bills whose table session started in the previous month, which
 * could hand out a number twice across a month boundary.
 *
 * The row is the mechanism: `SELECT … FOR UPDATE` serialises every issuance on it across
 * transactions and processes, and the increment commits with the bill or rolls back with it, so a
 * failed bill returns its number and the sequence stays gapless. Under REPEATABLE_READ a waiter
 * that lost the race gets a serialization failure (40001) rather than a stale value, and the
 * callers' `withRetryThrows` re-runs it with a fresh snapshot.
 *
 * [nextValue] is what the NEXT issuance hands out.
 */
object BillNumberSequenceTable : LongIdTable("bill_number_sequence", "id") {
    val period = varchar("period", 4)
    val nextValue = long("next_value")

    init {
        uniqueIndex(period)
    }
}
