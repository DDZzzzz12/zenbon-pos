package org.example.project.features.salesinvoice.domain.models

import org.example.project.features.salesinvoice.database.tables.SalesInvoiceVatBreakdownTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for one BG-23 VAT breakdown row. See [SalesInvoiceVatBreakdownTable]. */
class SalesInvoiceVatBreakdown(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<SalesInvoiceVatBreakdown>(SalesInvoiceVatBreakdownTable)

    var salesInvoice by SalesInvoice referencedOn SalesInvoiceVatBreakdownTable.salesInvoiceId
    var vatCategoryCode by SalesInvoiceVatBreakdownTable.vatCategoryCode
    var vatRate by SalesInvoiceVatBreakdownTable.vatRate
    var taxableAmount by SalesInvoiceVatBreakdownTable.taxableAmount
    var vatAmount by SalesInvoiceVatBreakdownTable.vatAmount
    var exemptionReasonText by SalesInvoiceVatBreakdownTable.exemptionReasonText

    override fun toString(): String = "SalesInvoiceVatBreakdown(id=$id, rate=$vatRate)"
}
