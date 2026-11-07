package org.example.project.features.partner.database.tables

import org.example.project.features.setup.database.tables.ShopInfoTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A company we deal with, independent of which direction money flows.
 *
 * `supplier` and `debtor` are **roles** on this record, not separate companies. One firm we both buy
 * from and sell to is one row here with two role rows, which is what lets the master-data handover
 * name it once — see the business partner design spec.
 *
 * Shop-scoped, matching `debtor`, `contract` and `bank_account`. Creditors are global today; this
 * narrows them, which is the conservative direction and the one the multi-shop foundation asks for.
 *
 * This is master data, NOT a snapshot. `invoice.seller_*` keeps whatever the supplier asserted, so
 * renaming a party never rewrites what an old invoice says.
 */
object BusinessPartnerTable : LongIdTable("business_partner", "id") {
    val shopId = reference("shop_id", ShopInfoTable)

    val name = varchar("name", 255)
    val legalName = varchar("legal_name", 255).nullable()

    /**
     * Deliberately NOT unique: duplicate parties for the same VAT id are expected to exist
     * transiently until a human merges them via [mergedIntoPartnerId] — a unique index here would
     * make that merge workflow impossible to reach. The dropped `supplier.vat_id` unique index was
     * replaced by `SupplierTable`'s `uniqueIndex(partnerId)` — one creditor role per company —
     * rather than moved here, for exactly that reason.
     */
    val vatId = varchar("vat_id", 50).nullable()
    val taxNumber = varchar("tax_number", 50).nullable()
    val street = varchar("street", 255).nullable()
    val zip = varchar("zip", 20).nullable()
    val city = varchar("city", 100).nullable()
    val country = varchar("country", 10).nullable()
    val email = varchar("email", 255).nullable()
    val phone = varchar("phone", 50).nullable()

    /**
     * [name] lower-cased with legal forms and punctuation removed — the last rung of the resolution
     * ladder. Stored rather than computed so the lookup is an index hit.
     */
    val normalizedName = varchar("normalized_name", 255).clientDefault { "" }

    /**
     * Set when this party was merged into another. The row is **kept** so the merge is reversible and
     * so a read addressed to this id can follow the pointer instead of 404ing.
     */
    val mergedIntoPartnerId =
        optReference("merged_into_partner_id", BusinessPartnerTable, onDelete = ReferenceOption.SET_NULL)

    val isActive = bool("is_active").default(true)
    val notes = text("notes").nullable()

    /** `clientDefault`, never `default(value)` — the latter freezes every row to server-start time. */
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at").nullable()

    init {
        index("idx_business_partner_normalized_name", false, normalizedName)
        index("idx_business_partner_vat_id", false, vatId)
        index("idx_business_partner_shop", false, shopId)
        index("idx_business_partner_merged_into", false, mergedIntoPartnerId)
    }
}
