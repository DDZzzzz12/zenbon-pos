package org.example.project.features.supplier.domain.models

import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.supplier.database.tables.SupplierTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for a creditor. See [SupplierTable] for why this is not a `Customer`. */
class Supplier(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Supplier>(SupplierTable)

    var partner by BusinessPartner referencedOn SupplierTable.partnerId
    var creditorNo by SupplierTable.creditorNo
    var sepaCreditorId by SupplierTable.sepaCreditorId
    var defaultAccountNo by SupplierTable.defaultAccountNo
    var defaultBuCode by SupplierTable.defaultBuCode
    var defaultCostCenter by SupplierTable.defaultCostCenter
    var paymentTermsDays by SupplierTable.paymentTermsDays
    var isProvisional by SupplierTable.isProvisional
    var isActive by SupplierTable.isActive
    var notes by SupplierTable.notes
    var createdAt by SupplierTable.createdAt
    var updatedAt by SupplierTable.updatedAt

    // Only the id: name and VAT id live on the party now, and reaching `partner` from `toString`
    // would fire a query from a log line.
    override fun toString(): String = "Supplier(id=$id)"
}
