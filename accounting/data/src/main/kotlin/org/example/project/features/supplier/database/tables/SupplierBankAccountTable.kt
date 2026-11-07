package org.example.project.features.supplier.database.tables

import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * An IBAN this supplier has been seen to use.
 *
 * The set is built up from invoices. Its purpose is twofold: it turns a bank transaction's
 * `counterparty_iban` into a supplier (match signal 4), and an invoice quoting an IBAN absent from
 * this set is the payment-redirection fraud signature, which the capture form warns about.
 */
object SupplierBankAccountTable : LongIdTable("supplier_bank_account", "id") {
    /**
     * The company this account belongs to.
     *
     * The account hangs off the party rather than the creditor role because an IBAN is an attribute
     * of the company: the same account receives our payments and sends theirs. Only the creditor
     * role can register one today, but the debtor role reads the same rows.
     */
    val partnerId = reference("partner_id", BusinessPartnerTable, onDelete = ReferenceOption.CASCADE)

    val iban = varchar("iban", 34)
    val bic = varchar("bic", 11).nullable()

    /**
     * The invoice this IBAN was first seen on; null when entered by hand.
     *
     * A bare `long`, NOT a `reference`, unlike every other FK-shaped column here — do not "fix" it.
     * `invoice` gains a `supplier_id` FK pointing the other way, so a real FK here would make the
     * two tables mutually dependent and leave `SchemaManager` with no valid creation order. This is
     * provenance, not a relationship: deleting an invoice must not cascade into master data.
     */
    val firstSeenOnInvoiceId = long("first_seen_on_invoice_id").nullable()

    /** A human confirmed this account belongs to the supplier. */
    val verified = bool("verified").default(false)

    init {
        index("idx_supplier_bank_account_partner", false, partnerId)
        uniqueIndex("idx_supplier_bank_account_partner_iban", partnerId, iban)
        index("idx_supplier_bank_account_iban", false, iban)
    }
}
