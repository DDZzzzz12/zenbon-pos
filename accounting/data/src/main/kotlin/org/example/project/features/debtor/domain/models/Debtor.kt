package org.example.project.features.debtor.domain.models

import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for a debtor. See [DebtorTable] for why this is not a `Customer`. */
class Debtor(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Debtor>(DebtorTable)

    var shop by ShopInfo referencedOn DebtorTable.shopId
    var partner by BusinessPartner referencedOn DebtorTable.partnerId
    var customerId by DebtorTable.customerId
    var debtorNo by DebtorTable.debtorNo
    var defaultRevenueAccountNo by DebtorTable.defaultRevenueAccountNo
    var defaultBuCode by DebtorTable.defaultBuCode
    var paymentTermsDays by DebtorTable.paymentTermsDays
    var isActive by DebtorTable.isActive
    var notes by DebtorTable.notes
    var einvoiceFormat by DebtorTable.einvoiceFormat
    var createdAt by DebtorTable.createdAt
    var updatedAt by DebtorTable.updatedAt

    override fun toString(): String = "Debtor(id=$id, debtorNo=$debtorNo)"
}
