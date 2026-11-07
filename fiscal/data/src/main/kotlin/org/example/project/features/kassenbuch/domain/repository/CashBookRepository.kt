package org.example.project.features.kassenbuch.domain.repository

import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.setup.domain.models.ShopInfo

/**
 * Data access for cash books. Requires an ambient Exposed transaction (no inner `dbQuery`).
 */
interface CashBookRepository {
    /**
     * The shop's fiscal Ladenkasse, creating it if absent.
     *
     * Creation-on-first-use rather than a shop-creation hook: V84 backfills the shops that
     * existed when it ran, and this covers everything after — including test fixtures that
     * build a `ShopInfo` directly. Idempotent.
     */
    fun fiscalBookFor(shop: ShopInfo): CashBook

    fun findById(id: Long): CashBook?

    fun findByShop(
        shop: ShopInfo,
        includeInactive: Boolean = false,
    ): List<CashBook>

    /** @throws IllegalStateException when [isFiscal] is true and the shop already has one. */
    fun create(
        shop: ShopInfo,
        name: String,
        isFiscal: Boolean,
        datevAccount: String,
        closeInterval: CashBookCloseInterval = CashBookCloseInterval.MANUAL,
    ): CashBook

    /** True when at least one kassenbuch_header references this book. */
    fun hasEntries(bookId: Long): Boolean

    /**
     * @throws IllegalStateException when the book already has entries, or when setting true
     *   while the shop already has a different fiscal book.
     */
    fun setFiscal(
        bookId: Long,
        isFiscal: Boolean,
    )
}
