package org.example.project.features.bank.database.tables

import org.example.project.features.setup.database.tables.ShopInfoTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * One of **our own** bank accounts — the counterpart to `supplier_bank_account`, which holds other
 * people's.
 *
 * This exists so a statement's IBAN stops being an ungoverned string. Three things depend on it:
 * import can say *which* account a statement belongs to instead of guessing; a transfer between two
 * of our own accounts becomes recognisable as an internal transfer rather than income; and a
 * statement's balance continuity can be checked per account.
 *
 * Several accounts per shop are modelled and not yet exercised — the shop has one today.
 */
object BankAccountTable : LongIdTable("bank_account", "id") {
    val shopId = reference("shop_id", ShopInfoTable)

    /** Unique: one row per real account, which is what makes IBAN → account resolution total. */
    val iban = varchar("iban", 34).uniqueIndex()
    val bic = varchar("bic", 11).nullable()
    val bankName = varchar("bank_name", 255).nullable()
    val accountHolder = varchar("account_holder", 255).nullable()

    /** SKR Sachkonto, e.g. 1200 — read by the DATEV export, not by matching. */
    val ledgerAccount = varchar("ledger_account", 20).nullable()

    val currency = varchar("currency", 3).default("EUR")
    val isActive = bool("is_active").default(true)

    /**
     * MUST stay `clientDefault { }`, not `default(value)` — the latter evaluates its argument once
     * at class-initialization time, freezing every row's timestamp to server start. It also emits
     * no DB DEFAULT, so V92 stays a plain NOT NULL column.
     */
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at").nullable()

    init {
        index("idx_bank_account_shop_id", false, shopId)
    }
}
