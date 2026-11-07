package org.example.project.features.salesinvoice.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Our bookkeeping decisions about an outgoing invoice — the mirror of `invoice_booking`, and the
 * layer that stays editable while the invoice itself freezes at issue.
 *
 * [bpAccountNo] is the Debitorenkonto **actually used**, frozen here rather than read live from
 * `debtor`: master data is editable, a posting that already went to the Steuerberater is not.
 * Resolution happens at posting time — `debtor.debtor_no`, then the shop's collective debtor, then
 * a hard failure naming the debtor. Never a generated fallback.
 */
object SalesInvoiceBookingTable : LongIdTable("sales_invoice_booking", "id") {
    val salesInvoiceId =
        reference("sales_invoice_id", SalesInvoiceTable, onDelete = ReferenceOption.CASCADE)

    val bpAccountNo = varchar("bp_account_no", 20).nullable()
    val revenueAccountNo = varchar("revenue_account_no", 20).nullable()
    val buCode = varchar("bu_code", 10).nullable()
    val costCenter = varchar("cost_center", 20).nullable()
    val bookingText = varchar("booking_text", 255).nullable()

    /** Set when the AR export has emitted this invoice. Frozen from then on. */
    val postedAt = datetime("posted_at").nullable()

    init {
        uniqueIndex("sales_invoice_booking_invoice_unique", salesInvoiceId)
    }
}
