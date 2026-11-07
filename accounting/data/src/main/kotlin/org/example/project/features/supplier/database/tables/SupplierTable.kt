package org.example.project.features.supplier.database.tables

import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A creditor — the counterparty on a supplier invoice.
 *
 * Deliberately separate from `customers`, which is a natural-person POS loyalty record. A supplier
 * is a company with a VAT ID, a DATEV Kreditorenkonto and several IBANs; nothing it needs overlaps.
 *
 * Identity — name, VAT ID, address — lives on [BusinessPartnerTable], reached through [partnerId];
 * this row holds only what is specific to *being a creditor*. [isProvisional] marks a row created
 * without a VAT ID — from a scan carrying only a name — which a human still has to confirm.
 *
 * This is master data, NOT a snapshot: `invoice.seller_*` keeps the supplier-asserted values, so
 * renaming a supplier's party never rewrites what an old invoice says.
 */
object SupplierTable : LongIdTable("supplier", "id") {
    /**
     * The company this creditor role belongs to.
     *
     * `RESTRICT` because deleting a party out from under a live creditor would orphan every invoice
     * pointing at it. Non-null: a creditor role with no company has no identity to carry.
     */
    val partnerId = reference("partner_id", BusinessPartnerTable, onDelete = ReferenceOption.RESTRICT)

    /** DATEV Kreditorenkonto — set once here, inherited by every future invoice's booking. */
    val creditorNo = varchar("creditor_no", 20).nullable()

    /**
     * SEPA Creditor Identifier (`CdtrSchmeId`, up to 35 characters) — the party *collecting* the
     * money, which is why it belongs to the supplier rather than to one agreement with them.
     *
     * Deliberately **not** [creditorNo]: that is the DATEV Kreditorenkonto, an unrelated number, and
     * matching a direct debit on it would be a silent category error.
     *
     * Null until an operator records it, and matching signal 3 stays silent while it is — a
     * suggestion built on a guess is the thing the ladder exists not to produce. A later slice
     * *could* learn it: the first time a direct debit carrying a creditor id is allocated to a
     * supplier, that pairing is evidence. That is a possibility, not a promise.
     */
    val sepaCreditorId = varchar("sepa_creditor_id", 35).nullable()

    /** Defaults that pre-fill the capture form. */
    val defaultAccountNo = varchar("default_account_no", 20).nullable()
    val defaultBuCode = varchar("default_bu_code", 10).nullable()
    val defaultCostCenter = varchar("default_cost_center", 20).nullable()
    val paymentTermsDays = integer("payment_terms_days").nullable()

    /** Created without a VAT ID; awaiting human confirmation. */
    val isProvisional = bool("is_provisional").default(false)
    val isActive = bool("is_active").default(true)
    val notes = text("notes").nullable()

    /**
     * MUST stay `clientDefault { }`, not `default(value)` — the latter evaluates its argument once
     * at class-initialization time, freezing every row's timestamp to server start. It also emits
     * no DB DEFAULT, so V90 stays a plain NOT NULL column.
     */
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at").nullable()

    init {
        /**
         * One creditor role per company — the invariant that replaces the dropped
         * `uniqueIndex(vatId)`.
         *
         * The party's `vatId` is deliberately NOT unique (two firms sharing one stay two parties
         * until a human merges them), so the uniqueness moved to the thing that is actually true and
         * actually relied upon: the creditor lookup for a party picks `firstOrNull()`, and the
         * payment-matching join on `partner_id` is ambiguous without this.
         */
        uniqueIndex("idx_supplier_partner_unique", partnerId)
        index("idx_supplier_is_provisional", false, isProvisional)
    }
}
