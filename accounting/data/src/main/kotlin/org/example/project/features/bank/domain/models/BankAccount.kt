package org.example.project.features.bank.domain.models

import org.example.project.features.bank.database.tables.BankAccountTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for one of our own bank accounts. See [BankAccountTable]. */
class BankAccount(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<BankAccount>(BankAccountTable)

    var shop by ShopInfo referencedOn BankAccountTable.shopId
    var iban by BankAccountTable.iban
    var bic by BankAccountTable.bic
    var bankName by BankAccountTable.bankName
    var accountHolder by BankAccountTable.accountHolder
    var ledgerAccount by BankAccountTable.ledgerAccount
    var currency by BankAccountTable.currency
    var isActive by BankAccountTable.isActive
    var createdAt by BankAccountTable.createdAt
    var updatedAt by BankAccountTable.updatedAt

    override fun toString(): String = "BankAccount(id=$id, iban=$iban, bank=$bankName)"
}
