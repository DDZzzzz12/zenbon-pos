package org.example.project.features.salesinvoice.database.tables

import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * EN 16931 BG-20 document-level allowance / BG-21 charge of an outgoing invoice.
 *
 * A from-bill invoice carries one allowance per promotion (Aktion) the bill took off, per VAT key:
 * the lines stay at the price charged and the VAT breakdown — copied from the bill — has already
 * absorbed the allowance. [amount] is BT-92/BT-99 and **net**, as EN 16931 states it;
 * [vatAmount] and [grossAmount] keep the till's VAT-inclusive deduction so the PDF can print it on
 * the same gross basis as the lines. Allowances only today; [isCharge] keeps the EN 16931 shape.
 */
object SalesInvoiceAllowanceChargeTable : LongIdTable("sales_invoice_allowance_charge", "id") {
    val salesInvoiceId =
        reference("sales_invoice_id", SalesInvoiceTable, onDelete = ReferenceOption.CASCADE)

    /** Print and XML order within the invoice. */
    val position = integer("position")
    val isCharge = bool("is_charge")

    /** BT-92 / BT-99, net. */
    val amount = decimal("amount", 19, 4)

    /** BT-93 / BT-100. */
    val baseAmount = decimal("base_amount", 19, 4).nullable()

    /** BT-94 / BT-101, percent units. */
    val percentage = decimal("percentage", 5, 2).nullable()

    /** BT-95 / BT-102, UNCL5305. */
    val vatCategoryCode = varchar("vat_category_code", 5)

    /** BT-96 / BT-103, percent units. */
    val vatRate = decimal("vat_rate", 5, 2)
    val vatAmount = decimal("vat_amount", 19, 4)
    val grossAmount = decimal("gross_amount", 19, 4)

    /** BT-97 / BT-104, e.g. `Aktion: Happy Hour`. */
    val reasonText = varchar("reason_text", 500).nullable()

    /** BT-98 / BT-105, UNTDID 5189 (allowance) or 7161 (charge). */
    val reasonCode = varchar("reason_code", 10).nullable()

    /** The till promotion this allowance renders; audit only, never the source of a printed value. */
    val billPromotionId =
        optReference("bill_promotion_id", BillPromotionTable, onDelete = ReferenceOption.SET_NULL)

    init {
        index("idx_sales_invoice_allowance_charge_invoice_id", false, salesInvoiceId)
        uniqueIndex("sales_invoice_allowance_charge_position_unique", salesInvoiceId, position)
    }
}
