package org.example.project.features.salesinvoice.domain.models

import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceAllowanceChargeTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceBookingTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceLineTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceVatBreakdownTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for an outgoing invoice. See [SalesInvoiceTable]. */
class SalesInvoice(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<SalesInvoice>(SalesInvoiceTable)

    var shop by ShopInfo referencedOn SalesInvoiceTable.shopId
    var invoiceNumber by SalesInvoiceTable.invoiceNumber
    var status by SalesInvoiceTable.status
    var revenueOrigin by SalesInvoiceTable.revenueOrigin
    var invoiceTypeCode by SalesInvoiceTable.invoiceTypeCode
    var precedingSalesInvoice by SalesInvoice optionalReferencedOn SalesInvoiceTable.precedingSalesInvoiceId
    var billId by SalesInvoiceTable.billId
    var debtor by Debtor optionalReferencedOn SalesInvoiceTable.debtorId

    var buyerName by SalesInvoiceTable.buyerName
    var buyerVatId by SalesInvoiceTable.buyerVatId
    var buyerStreet by SalesInvoiceTable.buyerStreet
    var buyerZip by SalesInvoiceTable.buyerZip
    var buyerCity by SalesInvoiceTable.buyerCity
    var buyerCountry by SalesInvoiceTable.buyerCountry
    var buyerEmail by SalesInvoiceTable.buyerEmail

    var sellerName by SalesInvoiceTable.sellerName
    var sellerVatId by SalesInvoiceTable.sellerVatId
    var sellerTaxNumber by SalesInvoiceTable.sellerTaxNumber
    var sellerStreet by SalesInvoiceTable.sellerStreet
    var sellerZip by SalesInvoiceTable.sellerZip
    var sellerCity by SalesInvoiceTable.sellerCity
    var sellerCountry by SalesInvoiceTable.sellerCountry

    var issueDate by SalesInvoiceTable.issueDate
    var serviceDate by SalesInvoiceTable.serviceDate
    var dueDate by SalesInvoiceTable.dueDate
    var currency by SalesInvoiceTable.currency

    var lineNetTotal by SalesInvoiceTable.lineNetTotal
    var allowanceTotal by SalesInvoiceTable.allowanceTotal
    var chargeTotal by SalesInvoiceTable.chargeTotal
    var taxExclusiveTotal by SalesInvoiceTable.taxExclusiveTotal
    var taxTotal by SalesInvoiceTable.taxTotal
    var taxInclusiveTotal by SalesInvoiceTable.taxInclusiveTotal
    var prepaidAmount by SalesInvoiceTable.prepaidAmount
    var amountDue by SalesInvoiceTable.amountDue
    var openAmount by SalesInvoiceTable.openAmount

    var paymentTermsText by SalesInvoiceTable.paymentTermsText
    var paymentReference by SalesInvoiceTable.paymentReference
    var notes by SalesInvoiceTable.notes

    var tseSerialNumber by SalesInvoiceTable.tseSerialNumber
    var tseTransactionNumber by SalesInvoiceTable.tseTransactionNumber
    var tseSignatureCounter by SalesInvoiceTable.tseSignatureCounter
    var tseSignature by SalesInvoiceTable.tseSignature
    var tseSignatureAlgorithm by SalesInvoiceTable.tseSignatureAlgorithm
    var tseLogTimeFormat by SalesInvoiceTable.tseLogTimeFormat
    var tseProcessType by SalesInvoiceTable.tseProcessType
    var tseStartTime by SalesInvoiceTable.tseStartTime
    var tseEndTime by SalesInvoiceTable.tseEndTime
    var tseTimestamp by SalesInvoiceTable.tseTimestamp
    var tseStartTimestamp by SalesInvoiceTable.tseStartTimestamp
    var tseQrCodePayload by SalesInvoiceTable.tseQrCodePayload

    var issuedAt by SalesInvoiceTable.issuedAt
    var issuedBy by SalesInvoiceTable.issuedBy
    var cancelledAt by SalesInvoiceTable.cancelledAt
    var cancelledBy by SalesInvoiceTable.cancelledBy
    var cancellationReason by SalesInvoiceTable.cancellationReason

    var documentId by SalesInvoiceTable.documentId
    var createdAt by SalesInvoiceTable.createdAt
    var updatedAt by SalesInvoiceTable.updatedAt

    val lines by SalesInvoiceLine referrersOn SalesInvoiceLineTable.salesInvoiceId
    val vatBreakdown by SalesInvoiceVatBreakdown referrersOn SalesInvoiceVatBreakdownTable.salesInvoiceId

    /** BG-20 / BG-21 — document-level allowances and charges, unordered; sort by `position`. */
    val allowanceCharges by SalesInvoiceAllowanceCharge referrersOn SalesInvoiceAllowanceChargeTable.salesInvoiceId

    /** The single booking row, when one exists — `sales_invoice_booking` is unique per invoice. */
    val booking by SalesInvoiceBooking referrersOn SalesInvoiceBookingTable.salesInvoiceId

    override fun toString(): String = "SalesInvoice(id=$id, number=$invoiceNumber, status=$status)"
}
