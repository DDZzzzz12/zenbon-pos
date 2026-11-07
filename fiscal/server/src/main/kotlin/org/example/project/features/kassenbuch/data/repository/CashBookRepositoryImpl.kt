package org.example.project.features.kassenbuch.data.repository

import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.features.kassenbuch.database.tables.CashBookTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.repository.CashBookRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

class CashBookRepositoryImpl : CashBookRepository {
    override fun fiscalBookFor(shop: ShopInfo): CashBook =
        CashBook
            .find { (CashBookTable.shopId eq shop.id) and (CashBookTable.isFiscal eq true) }
            .firstOrNull()
            ?: create(shop, name = "Ladenkasse", isFiscal = true, datevAccount = "1000")

    override fun findById(id: Long): CashBook? = CashBook.findById(id)

    override fun findByShop(
        shop: ShopInfo,
        includeInactive: Boolean,
    ): List<CashBook> =
        CashBook
            .find { CashBookTable.shopId eq shop.id }
            .orderBy(CashBookTable.sortOrder to SortOrder.ASC, CashBookTable.id to SortOrder.ASC)
            .filter { includeInactive || it.isActive }

    override fun create(
        shop: ShopInfo,
        name: String,
        isFiscal: Boolean,
        datevAccount: String,
        closeInterval: CashBookCloseInterval,
    ): CashBook {
        if (isFiscal) {
            val existing =
                CashBook
                    .find { (CashBookTable.shopId eq shop.id) and (CashBookTable.isFiscal eq true) }
                    .firstOrNull()
            check(existing == null) {
                "Shop ${shop.id.value} already has a fiscal cash book (id=${existing?.id?.value})"
            }
        }
        return CashBook.new {
            this.shop = shop
            this.name = name
            this.isFiscal = isFiscal
            this.datevAccount = datevAccount
            this.closeInterval = closeInterval
        }
    }

    override fun hasEntries(bookId: Long): Boolean =
        !KassenbuchHeaderTable
            .selectAll()
            .where { KassenbuchHeaderTable.cashBookId eq bookId }
            .limit(1)
            .empty()

    override fun setFiscal(
        bookId: Long,
        isFiscal: Boolean,
    ) {
        val book = CashBook.findById(bookId) ?: error("Cash book $bookId not found")
        if (book.isFiscal == isFiscal) return

        // Immutable once used: NOT_APPLICABLE on existing entries is a stored consequence of
        // this flag, and flipping it would make those rows contradict their book.
        check(!hasEntries(bookId)) {
            "Cannot change isFiscal on cash book $bookId — it already has entries"
        }
        if (isFiscal) {
            val existing =
                CashBook
                    .find { (CashBookTable.shopId eq book.shop.id) and (CashBookTable.isFiscal eq true) }
                    .firstOrNull()
            check(existing == null) { "Shop already has a fiscal cash book (id=${existing?.id?.value})" }
        }
        book.isFiscal = isFiscal
    }
}
