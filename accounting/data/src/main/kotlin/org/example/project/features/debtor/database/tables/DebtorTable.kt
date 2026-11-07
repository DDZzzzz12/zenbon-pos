package org.example.project.features.debtor.database.tables

import org.example.project.core.domain.enums.salesinvoice.EInvoiceFormatPreference
import org.example.project.features.customer.database.tables.CustomerTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.setup.database.tables.ShopInfoTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A customer we invoice — the AR mirror of `supplier`.
 *
 * Identity — name, VAT ID, address — lives on [BusinessPartnerTable], reached through [partnerId];
 * this row holds only what is specific to *being a debtor*.
 *
 * Separate from `customers` on purpose. That table is a CRM/loyalty record whose GDPR erasure
 * lifecycle collides with the ten-year §147 AO retention of an invoice recipient: a customer asking
 * to be deleted must not delete their invoices. [customerId] links the two when they are the same
 * person, and nulls out when the loyalty record is erased.
 *
 * There is deliberately **no `is_provisional`**. On the supplier side that flag exists because
 * parsing auto-creates suppliers from whatever a PDF asserted; a debtor is only ever created by a
 * human typing it.
 */
object DebtorTable : LongIdTable("debtor", "id") {
    val shopId = reference("shop_id", ShopInfoTable)

    /**
     * The company this debtor role belongs to. See `SupplierTable.partnerId` — same contract, and
     * the two together are what let one firm be both without being two companies.
     */
    val partnerId = reference("partner_id", BusinessPartnerTable, onDelete = ReferenceOption.RESTRICT)

    /** The loyalty record, when this debtor is also a known guest. SET NULL on erasure. */
    val customerId = optReference("customer_id", CustomerTable, onDelete = ReferenceOption.SET_NULL)

    /**
     * DATEV Debitorenkonto. Nullable and never auto-generated on creation: the Personenkonto
     * namespace belongs to the Steuerberater, and inventing 10001 for a customer they already
     * numbered 10247 makes every export need remapping. Assigned only when an invoice actually
     * posts to AR — a `POS_SETTLED` invoice never asks for one.
     */
    val debtorNo = varchar("debtor_no", 20).nullable()

    /** Defaults that pre-fill the invoice form. */
    val defaultRevenueAccountNo = varchar("default_revenue_account_no", 20).nullable()
    val defaultBuCode = varchar("default_bu_code", 10).nullable()
    val paymentTermsDays = integer("payment_terms_days").nullable()

    val isActive = bool("is_active").default(true)
    val notes = text("notes").nullable()

    /** How this debtor prefers to receive their invoices. See [EInvoiceFormatPreference]. Slice 4e. */
    val einvoiceFormat =
        enumerationByName("einvoice_format", 20, EInvoiceFormatPreference::class)
            .default(EInvoiceFormatPreference.NONE)

    /**
     * MUST stay `clientDefault { }`, not `default(value)` — the latter evaluates once at
     * class-initialization time, freezing every row's timestamp to server start, and emits no DB
     * DEFAULT.
     */
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at").nullable()

    init {
        index("idx_debtor_shop_id", false, shopId)

        /**
         * One debtor role per company — the AR mirror of `SupplierTable`'s `uniqueIndex(partnerId)`,
         * added by the business-partner migration.
         *
         * It was a plain index until then, which was an omission rather than a decision: the model
         * has always meant one debtor role per party, and the debtor lookup for a party reads it as
         * one row. The custom-export schema graph reads unique indexes to decide whether a relation
         * may be LEFT joined, so a non-unique index here meant the Debitorennummer could never be
         * offered on a partner export — a relation hidden by a missing index.
         *
         * This declaration and the migration travel together: this is what the graph reads, and
         * the SQL is what the database enforces.
         */
        uniqueIndex("idx_debtor_partner_unique", partnerId)
        index("idx_debtor_debtor_no", false, debtorNo)
        index("idx_debtor_customer_id", false, customerId)
    }
}
