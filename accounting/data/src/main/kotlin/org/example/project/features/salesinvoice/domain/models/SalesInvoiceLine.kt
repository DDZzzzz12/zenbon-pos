package org.example.project.features.salesinvoice.domain.models

import org.example.project.features.salesinvoice.database.tables.SalesInvoiceLineTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for one outgoing-invoice line. See [SalesInvoiceLineTable]. */
class SalesInvoiceLine(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<SalesInvoiceLine>(SalesInvoiceLineTable)

    var salesInvoice by SalesInvoice referencedOn SalesInvoiceLineTable.salesInvoiceId
    var lineNo by SalesInvoiceLineTable.lineNo
    var itemName by SalesInvoiceLineTable.itemName
    var itemDescription by SalesInvoiceLineTable.itemDescription
    var quantity by SalesInvoiceLineTable.quantity
    var unitCode by SalesInvoiceLineTable.unitCode
    var netUnitPrice by SalesInvoiceLineTable.netUnitPrice
    var lineNetAmount by SalesInvoiceLineTable.lineNetAmount
    var vatRate by SalesInvoiceLineTable.vatRate
    var vatCategoryCode by SalesInvoiceLineTable.vatCategoryCode
    var vatAmount by SalesInvoiceLineTable.vatAmount
    var lineGrossAmount by SalesInvoiceLineTable.lineGrossAmount
    var grossUnitPrice by SalesInvoiceLineTable.grossUnitPrice
    var sourceOrderItemId by SalesInvoiceLineTable.sourceOrderItemId

    override fun toString(): String = "SalesInvoiceLine(id=$id, lineNo=$lineNo, itemName=$itemName)"
}
