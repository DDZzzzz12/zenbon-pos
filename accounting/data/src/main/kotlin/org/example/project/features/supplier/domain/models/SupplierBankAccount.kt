package org.example.project.features.supplier.domain.models

import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for one known supplier IBAN. */
class SupplierBankAccount(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<SupplierBankAccount>(SupplierBankAccountTable)

    var partner by BusinessPartner referencedOn SupplierBankAccountTable.partnerId
    var iban by SupplierBankAccountTable.iban
    var bic by SupplierBankAccountTable.bic
    var firstSeenOnInvoiceId by SupplierBankAccountTable.firstSeenOnInvoiceId
    var verified by SupplierBankAccountTable.verified

    override fun toString(): String = "SupplierBankAccount(id=$id, iban=$iban)"
}
