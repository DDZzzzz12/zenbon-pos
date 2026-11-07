package org.example.project.features.salesinvoice.domain.models

import org.example.project.features.salesinvoice.database.tables.SalesInvoiceAllowanceChargeTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for one BG-20 allowance / BG-21 charge. See [SalesInvoiceAllowanceChargeTable]. */
class SalesInvoiceAllowanceCharge(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<SalesInvoiceAllowanceCharge>(SalesInvoiceAllowanceChargeTable)

    var salesInvoice by SalesInvoice referencedOn SalesInvoiceAllowanceChargeTable.salesInvoiceId
    var position by SalesInvoiceAllowanceChargeTable.position
    var isCharge by SalesInvoiceAllowanceChargeTable.isCharge
    var amount by SalesInvoiceAllowanceChargeTable.amount
    var baseAmount by SalesInvoiceAllowanceChargeTable.baseAmount
    var percentage by SalesInvoiceAllowanceChargeTable.percentage
    var vatCategoryCode by SalesInvoiceAllowanceChargeTable.vatCategoryCode
    var vatRate by SalesInvoiceAllowanceChargeTable.vatRate
    var vatAmount by SalesInvoiceAllowanceChargeTable.vatAmount
    var grossAmount by SalesInvoiceAllowanceChargeTable.grossAmount
    var reasonText by SalesInvoiceAllowanceChargeTable.reasonText
    var reasonCode by SalesInvoiceAllowanceChargeTable.reasonCode
    var billPromotionId by SalesInvoiceAllowanceChargeTable.billPromotionId

    override fun toString(): String = "SalesInvoiceAllowanceCharge(id=$id, position=$position, isCharge=$isCharge)"
}
