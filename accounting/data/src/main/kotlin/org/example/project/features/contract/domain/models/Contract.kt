package org.example.project.features.contract.domain.models

import org.example.project.features.contract.database.tables.ContractTable
import org.example.project.features.document.domain.models.Document
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.domain.models.Supplier
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/**
 * Exposed DAO entity for a recurring obligation. See [ContractTable] for why [expectsInvoice]
 * separates the two kinds this model must both support.
 */
class Contract(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Contract>(ContractTable)

    var shop by ShopInfo referencedOn ContractTable.shopId
    var supplier by Supplier optionalReferencedOn ContractTable.supplierId

    var name by ContractTable.name
    var description by ContractTable.description

    var expectedAmount by ContractTable.expectedAmount
    var tolerancePct by ContractTable.tolerancePct
    var toleranceAbs by ContractTable.toleranceAbs

    /** [ContractInterval] name. */
    var interval by ContractTable.interval

    /** 1–31; a value past the end of a short month clamps to the last day. */
    var dueDay by ContractTable.dueDay

    var dueWeekday by ContractTable.dueWeekday

    var startDate by ContractTable.startDate
    var endDate by ContractTable.endDate
    var noticePeriodDays by ContractTable.noticePeriodDays

    var expectsInvoice by ContractTable.expectsInvoice

    /** SEPA mandate reference — see [ContractTable.sepaMandateId] for why it lives here. */
    var sepaMandateId by ContractTable.sepaMandateId

    var defaultAccountNo by ContractTable.defaultAccountNo
    var defaultBuCode by ContractTable.defaultBuCode
    var defaultCostCenter by ContractTable.defaultCostCenter

    /** The contract PDF itself, if one was filed. */
    var document by Document optionalReferencedOn ContractTable.documentId

    var isActive by ContractTable.isActive

    var createdAt by ContractTable.createdAt
    var updatedAt by ContractTable.updatedAt

    override fun toString(): String = "Contract(id=$id, name=$name, interval=$interval, dueDay=$dueDay)"
}
