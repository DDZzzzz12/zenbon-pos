package org.example.project.features.order.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * The order-number counter behind `t_order.order_number` (`ORD-yyyyMMdd-N`), mirroring
 * [org.example.project.features.bill.database.tables.BillNumberSequenceTable].
 *
 * N used to be `max(t_order.id) + 1`, read inside the creating transaction. Two cashiers creating
 * an order at the same moment both read the same maximum and drew the same N; the in-process
 * `OrderNumberTracker` could not stop it, because it released the reservation before the
 * transaction committed, and the loser died on `t_order_order_number_unique` with a 500.
 *
 * The row is the mechanism: `SELECT … FOR UPDATE` serialises every issuance on it across
 * transactions and processes, and the increment commits with the order or rolls back with it. Under
 * REPEATABLE_READ a waiter that lost the race gets a serialization failure (40001) rather than a
 * stale value, and `createOrder`'s `withRetryThrows` re-runs it with a fresh snapshot.
 *
 * There is a single row, `prefix = 'ORD'`: N is one global counter, not reset per day, exactly as
 * before. [nextValue] is what the NEXT issuance hands out.
 */
object OrderNumberSequenceTable : LongIdTable("order_number_sequence", "id") {
    val prefix = varchar("prefix", 10)
    val nextValue = long("next_value")

    init {
        uniqueIndex(prefix)
    }
}
