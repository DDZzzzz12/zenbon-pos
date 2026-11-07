package org.example.project.features.kassenbuch.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.example.project.features.kassenbuch.database.tables.CashBookCloseTable
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.models.CashBookClose
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq

/**
 * "When was this cash book last closed?" — the single place that branches on [CashBook.isFiscal]
 * to answer it.
 *
 * The fiscal Ladenkasse is closed by a Z-Bon, the legally defined Kassenabschluss; every other
 * book is closed by a Kassensturz, recorded as a `CashBookClose` row. The two are deliberately
 * NOT unified into one table — a Z-Bon writing a parallel `cash_book_close` row would be a second
 * source of truth for the same event, at a different cadence, from inside the transaction this
 * whole cash-book-dimension effort avoids disturbing (`ZBonServiceImpl.createReport`). Every
 * consumer that needs "closed when" would otherwise repeat the `if (isFiscal) ... else ...`
 * branch; this resolver confines it to exactly one place.
 *
 * Read-only — writes nothing. Requires an ambient Exposed transaction, same as
 * `org.example.project.features.zBon.domain.OpenBusinessCheck`.
 */
object CashBookCloseStatusResolver {
    /** The book's most recent closing, or null if it has never been closed. */
    fun lastClose(cashBookId: Long): CashBookCloseStatus? {
        val book = CashBook.findById(cashBookId) ?: return null
        return if (book.isFiscal) lastZBonClose(book) else lastKassensturzClose(cashBookId)
    }

    /** The still-open period: (day after the last close .. today), or null if never closed. */
    fun openPeriod(cashBookId: Long): ClosedRange<LocalDate>? {
        val last = lastClose(cashBookId) ?: return null
        val start = last.periodEnd.plus(1, DateTimeUnit.DAY)
        return start..currentTimeStamp().date
    }

    /**
     * The fiscal book is shop-wide, not per-book: there is exactly one fiscal book per shop, so
     * that shop's latest Z-Bon IS that book's last close. `ZBonTable` carries no `cashBookId` to
     * filter on — a Z-Bon is not scoped to a cash book at all — so the scoping is done by SHOP.
     *
     * The shop filter matters even though the system is single-shop today: without it, asking a
     * book about its last close would answer from *any* shop's Z-Bon, which is the same
     * cross-tenant leak already fixed once in `KassenbuchRepositoryImpl.getCurrentBalance`. The
     * book knows its shop, so scoping costs nothing and the design is documented as multi-shop
     * ready.
     *
     * Ordered by `zbonNummer` DESC, the Z-Bon's own sequence, mirroring
     * `OpenBusinessCheck.hasOpenBills`.
     */
    private fun lastZBonClose(book: CashBook): CashBookCloseStatus? {
        val zBon =
            ZBon
                .find { ZBonTable.shopInfoId eq book.shop.id }
                .maxByOrNull { it.zbonNummer }
                ?: return null
        return CashBookCloseStatus(
            cashBookId = book.id.value,
            closeKind = CloseKind.Z_BON,
            closedAt = zBon.endTime,
            periodEnd = zBon.endTime.date,
            balanceAtClose = zBon.closingBalance,
            countedBalance = null,
        )
    }

    private fun lastKassensturzClose(cashBookId: Long): CashBookCloseStatus? {
        val close =
            CashBookClose
                .find { CashBookCloseTable.cashBookId eq cashBookId }
                .orderBy(CashBookCloseTable.periodEnd to SortOrder.DESC, CashBookCloseTable.id to SortOrder.DESC)
                .firstOrNull()
                ?: return null
        return CashBookCloseStatus(
            cashBookId = cashBookId,
            closeKind = CloseKind.KASSENSTURZ,
            closedAt = close.closedAt,
            periodEnd = close.periodEnd,
            balanceAtClose = close.bookBalance,
            countedBalance = close.countedBalance,
        )
    }
}
