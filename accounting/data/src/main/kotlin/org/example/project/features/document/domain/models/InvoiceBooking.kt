/**
 * Invoice Booking Entity - Exposed DAO
 *
 * Domain model representing how we book an invoice — not what the supplier asserted.
 * 1:1 with [Invoice], kept separate so an edit form that owns these fields structurally
 * cannot null parsed supplier data.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.models

import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for our bookkeeping decisions on an invoice.
 *
 * This is what the DATEV Eingangsrechnungen export reads.
 */
class InvoiceBooking(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<InvoiceBooking>(InvoiceBookingTable)

    /** Parent invoice. 1:1. */
    var invoice by Invoice referencedOn InvoiceBookingTable.invoiceId

    /** Gegenkonto. */
    var accountNo by InvoiceBookingTable.accountNo

    /** Kreditorenkonto. */
    var bpAccountNo by InvoiceBookingTable.bpAccountNo

    var buCode by InvoiceBookingTable.buCode
    var bookingText by InvoiceBookingTable.bookingText
    var costCategoryId by InvoiceBookingTable.costCategoryId
    var costCategoryId2 by InvoiceBookingTable.costCategoryId2
    var costAmount by InvoiceBookingTable.costAmount
    var typeOfReceivable by InvoiceBookingTable.typeOfReceivable
    var paymentDunningBlock by InvoiceBookingTable.paymentDunningBlock
    var paidAt by InvoiceBookingTable.paidAt

    override fun toString(): String = "InvoiceBooking(id=$id, invoiceId=${invoice.id.value}, accountNo=$accountNo)"
}
