package org.example.project.features.document.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** EN 16931 BG-20 allowance / BG-21 charge at document level. */
object InvoiceAllowanceChargeTable : LongIdTable("invoice_allowance_charge", "id") {
    val invoiceId = reference("invoice_id", InvoiceTable, onDelete = ReferenceOption.CASCADE)

    val isCharge = bool("is_charge")
    val amount = decimal("amount", 19, 4)
    val baseAmount = decimal("base_amount", 19, 4).nullable()
    val percentage = decimal("percentage", 5, 2).nullable()
    val reasonCode = varchar("reason_code", 50).nullable()
    val reasonText = varchar("reason_text", 500).nullable()
    val vatCategoryCode = varchar("vat_category_code", 5)
    val vatRate = decimal("vat_rate", 5, 2)

    init {
        index("idx_invoice_allowance_charge_invoice_id", false, invoiceId)
    }
}
