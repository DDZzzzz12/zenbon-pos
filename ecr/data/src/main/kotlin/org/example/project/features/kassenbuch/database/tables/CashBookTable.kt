package org.example.project.features.kassenbuch.database.tables

import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.features.setup.database.tables.ShopInfoTable
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A cash book: one physical cash location whose balance is tracked independently.
 *
 * Exactly one book per shop is fiscal (`isFiscal = true`) — the Ladenkasse, the register
 * drawer that §146a AO reaches. Its entries are TSE-signed, swept into the Z-Bon, and
 * exported to DSFinV-K. Every other book (Tresor, Nebenkasse) is ordinary bookkeeping:
 * no TSE, no DSFinV-K, closed by its own periodic Kassensturz.
 */
object CashBookTable : LongIdTable("cash_book", "id") {
    val shopId = reference("shop_id", ShopInfoTable)

    /** Display name, e.g. "Ladenkasse", "Tresor". */
    val name = varchar("name", 60)

    /**
     * Whether this book is the shop's fiscal register.
     *
     * **Immutable once the book has entries** (enforced later in CashBookRepository.setFiscal).
     * `KassenbuchHeader.tseSigningStatus = NOT_APPLICABLE` is a stored consequence of this
     * flag; letting it flip would make that stored value contradict the book.
     */
    val isFiscal = bool("is_fiscal")

    /** DATEV Sachkonto — 1000 Kasse (fiscal) / 1010 Nebenkasse (non-fiscal) by default. */
    val datevAccount = varchar("datev_account", 5)

    /** Per-book DATEV cost-centre defaults; an entry may override. */
    val kost1 = varchar("kost1", 36).nullable()
    val kost2 = varchar("kost2", 36).nullable()

    /** Close-cadence hint. Never enforced — see [CashBookCloseInterval]. */
    val closeInterval =
        enumerationByName<CashBookCloseInterval>("close_interval", 20)
            .default(CashBookCloseInterval.MANUAL)

    /** Deactivation is refused while the balance is non-zero, so cash cannot be stranded. */
    val isActive = bool("is_active").default(true)

    val sortOrder = integer("sort_order").default(0)

    /**
     * The till whose TSE client signs this book's closing movements (fiscal book only). Null = the
     * calling till signs. See `ClosingSigner`.
     */
    val signingTerminalId =
        reference(
            "signing_terminal_id",
            TerminalTable,
            onDelete = ReferenceOption.SET_NULL,
            fkName = "fk_cash_book_signing_terminal",
        ).nullable()

    /**
     * `clientDefault`, never `default(currentTimeStamp())` — `Column.default(value)` takes a
     * VALUE evaluated once at table-object init, freezing one wall-clock instant into the DDL
     * default (audit KABS-1, see KassenbuchHeaderTable.createdAt).
     */
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }

    init {
        index("idx_cash_book_shop", false, shopId)

        // Exactly one fiscal book per shop (V82). Partial, so any number of non-fiscal books may
        // share the shop. PostgreSQL only: Exposed skips a filtered index on H2, where the service
        // check is what holds. The predicate is the bare column, as V82 writes it, so a fresh and
        // an upgraded install carry the identical definition (FreshInstallIndexParityTest).
        uniqueIndex(
            "idx_cash_book_one_fiscal_per_shop",
            shopId,
            filterCondition = {
                object : Op<Boolean>() {
                    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                        queryBuilder.append(isFiscal)
                    }
                }
            },
        )
    }
}
