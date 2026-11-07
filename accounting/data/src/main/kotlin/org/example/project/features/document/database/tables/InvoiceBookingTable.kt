package org.example.project.features.document.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * How **we** book an invoice — not what the supplier asserted. 1:1 with [InvoiceTable].
 *
 * Kept separate so an edit form that owns these fields structurally cannot null parsed supplier
 * data. That removes a defect class rather than guarding against it.
 *
 * This is what the DATEV Eingangsrechnungen export reads.
 */
object InvoiceBookingTable : LongIdTable("invoice_booking", "id") {
    val invoiceId = reference("invoice_id", InvoiceTable, onDelete = ReferenceOption.CASCADE).uniqueIndex("idx_invoice_booking_invoice_id")

    /** Gegenkonto. */
    val accountNo = varchar("account_no", 20).nullable()

    /** Kreditorenkonto. */
    val bpAccountNo = varchar("bp_account_no", 20).nullable()

    val buCode = varchar("bu_code", 10).nullable()
    val bookingText = varchar("booking_text", 60).nullable()
    val costCategoryId = varchar("cost_category_id", 20).nullable()
    val costCategoryId2 = varchar("cost_category_id2", 20).nullable()
    val costAmount = decimal("cost_amount", 19, 4).nullable()
    val typeOfReceivable = varchar("type_of_receivable", 50).nullable()
    val paymentDunningBlock = bool("payment_dunning_block").default(false)
    val paidAt = datetime("paid_at").nullable()
}
