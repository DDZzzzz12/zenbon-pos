package org.example.project.features.salesinvoice.domain.service

import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID

/**
 * The one definition of "this bill already has an invoice".
 *
 * A `CANCELLED` invoice does not count: cancelling frees the bill's slot so a corrected invoice can
 * be issued. Both the invoiceability guard and the bill wire mapper read it here rather than each
 * spelling out the predicate — two copies would drift, and the drift would surface as a till that
 * offers an action the server refuses.
 *
 * **Requires an ambient transaction.**
 */
object LiveSalesInvoiceLookup {
    fun forBill(billId: Long): SalesInvoice? =
        SalesInvoice
            .find {
                (SalesInvoiceTable.billId eq EntityID(billId, BillTable)) and
                    (SalesInvoiceTable.status neq SalesInvoiceStatus.CANCELLED)
            }.firstOrNull()

    fun exists(billId: Long): Boolean = forBill(billId) != null
}
