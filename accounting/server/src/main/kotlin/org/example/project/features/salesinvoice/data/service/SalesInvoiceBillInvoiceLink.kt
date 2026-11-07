package org.example.project.features.salesinvoice.data.service

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.bill.domain.service.LiveBillInvoice
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.LiveSalesInvoiceLookup
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*

/** The accounting side of [BillInvoiceLink]: answers the bill's questions from the sales-invoice tables. */
class SalesInvoiceBillInvoiceLink : BillInvoiceLink {
    override fun liveInvoiceFor(billId: Long): LiveBillInvoice? =
        LiveSalesInvoiceLookup.forBill(billId)?.let { invoice ->
            LiveBillInvoice(
                id = invoice.id.value,
                invoiceNumber = invoice.invoiceNumber,
                openAmount = invoice.openAmount,
                issued = invoice.status == SalesInvoiceStatus.ISSUED,
            )
        }

    override fun deleteDraftInvoiceFor(billId: Long) {
        val invoice = LiveSalesInvoiceLookup.forBill(billId) ?: return
        if (invoice.status == SalesInvoiceStatus.DRAFT) invoice.delete()
    }

    override fun billLinksForInvoices(invoiceIds: Collection<Long>): Map<Long, BillDto.BillLinkDto> {
        if (invoiceIds.isEmpty()) return emptyMap()
        return SalesInvoiceTable
            .innerJoin(BillTable, { SalesInvoiceTable.billId }, { BillTable.id })
            .select(SalesInvoiceTable.id, BillTable.id, BillTable.billNumber)
            .where { SalesInvoiceTable.id.inList(invoiceIds.distinct().map { EntityID(it, SalesInvoiceTable) }) }
            .associate { it[SalesInvoiceTable.id].value to BillDto.BillLinkDto(it[BillTable.id].value, it[BillTable.billNumber]) }
    }

    override fun originalBillIdOfClearing(bill: Bill): Long? {
        val invoiceId = bill.clearsSalesInvoiceId ?: return null
        val invoice =
            checkNotNull(SalesInvoice.findById(invoiceId)) {
                "Bill ${bill.id.value} clears sales invoice $invoiceId, which does not exist"
            }
        return invoice.billId?.value
    }
}
