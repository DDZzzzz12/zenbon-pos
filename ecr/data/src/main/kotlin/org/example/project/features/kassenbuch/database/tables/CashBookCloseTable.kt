package org.example.project.features.kassenbuch.database.tables

import kotlinx.datetime.LocalDate
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A Kassensturz on a non-fiscal cash book: the operator's physical count compared against the
 * book balance, any difference booked, and the period locked.
 *
 * Never written for the fiscal book — that one is closed by the Z-Bon, which is the legally
 * defined Kassenabschluss. A parallel row here would be a second source of truth for the same
 * event, at a different cadence.
 */
object CashBookCloseTable : LongIdTable("cash_book_close", "id") {
    val cashBookId = reference("cash_book_id", CashBookTable)

    /** Derived from the previous close (or the book's first entry) — periods are gapless. */
    val periodStart = date("period_start")
    val periodEnd = date("period_end")

    /** What the book says should be there at [periodEnd]. */
    val bookBalance = decimal("book_balance", precision = 19, scale = 4)

    /** What the operator actually counted. */
    val countedBalance = decimal("counted_balance", precision = 19, scale = 4)

    /** `counted − book`. Zero when they agree. */
    val difference = decimal("difference", precision = 19, scale = 4)

    /** The CASH_SURPLUS/CASH_SHORTAGE entry booked for [difference]; null when zero. */
    val differenceHeaderId = optReference("difference_header_id", KassenbuchHeaderTable)

    val closedBy = reference("closed_by", EmployeeTable)

    /**
     * `clientDefault`, never `default(currentTimeStamp())` — `Column.default(value)` takes a
     * VALUE evaluated once at table-object init, freezing one wall-clock instant into the DDL
     * default (audit KABS-1; see `KassenbuchHeaderTable.createdAt`).
     */
    val closedAt = datetime("closed_at").clientDefault { currentTimeStamp() }
    val notes = varchar("notes", 500).nullable()

    init {
        // "Latest close of this book" (V82): the key carries period_end DESC, which Exposed can only
        // express as an index function. PostgreSQL only — Exposed skips a functional index on H2.
        index(
            "idx_cash_book_close_book",
            false,
            cashBookId,
            functions =
                listOf(
                    object : Function<LocalDate>(periodEnd.columnType) {
                        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                            queryBuilder.append("${periodEnd.name} DESC")
                        }
                    },
                ),
        )
    }
}
