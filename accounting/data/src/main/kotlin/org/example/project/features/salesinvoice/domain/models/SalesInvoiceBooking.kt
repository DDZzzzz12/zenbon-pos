package org.example.project.features.salesinvoice.domain.models

import org.example.project.features.salesinvoice.database.tables.SalesInvoiceBookingTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for our booking decisions about an outgoing invoice. */
class SalesInvoiceBooking(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<SalesInvoiceBooking>(SalesInvoiceBookingTable)

    var salesInvoice by SalesInvoice referencedOn SalesInvoiceBookingTable.salesInvoiceId
    var bpAccountNo by SalesInvoiceBookingTable.bpAccountNo
    var revenueAccountNo by SalesInvoiceBookingTable.revenueAccountNo
    var buCode by SalesInvoiceBookingTable.buCode
    var costCenter by SalesInvoiceBookingTable.costCenter
    var bookingText by SalesInvoiceBookingTable.bookingText
    var postedAt by SalesInvoiceBookingTable.postedAt

    override fun toString(): String = "SalesInvoiceBooking(id=$id, bpAccountNo=$bpAccountNo)"
}
