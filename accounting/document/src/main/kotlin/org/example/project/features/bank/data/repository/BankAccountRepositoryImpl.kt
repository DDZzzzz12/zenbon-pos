package org.example.project.features.bank.data.repository

import org.example.project.features.bank.database.tables.BankAccountTable
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.bank.domain.repository.BankAccountRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.jetbrains.exposed.v1.core.*

/** Exposed implementation of [BankAccountRepository]. Requires an ambient transaction. */
class BankAccountRepositoryImpl : BankAccountRepository {
    override fun create(
        shop: ShopInfo,
        iban: String,
        bic: String?,
        bankName: String?,
        accountHolder: String?,
        ledgerAccount: String?,
        currency: String,
    ): BankAccount =
        BankAccount.new {
            this.shop = shop
            this.iban = canonicalIban(iban)
            this.bic = bic?.takeIf { it.isNotBlank() }
            this.bankName = bankName?.takeIf { it.isNotBlank() }
            this.accountHolder = accountHolder?.takeIf { it.isNotBlank() }
            this.ledgerAccount = ledgerAccount?.takeIf { it.isNotBlank() }
            this.currency = currency
        }

    override fun findById(id: Long): BankAccount? = BankAccount.findById(id)

    override fun findByIban(iban: String?): BankAccount? {
        val key = iban?.let { canonicalIban(it) }?.takeIf { it.isNotBlank() } ?: return null
        return BankAccount.find { BankAccountTable.iban eq key }.firstOrNull()
    }

    override fun listActive(): List<BankAccount> =
        BankAccount
            .find { BankAccountTable.isActive eq true }
            .orderBy(BankAccountTable.bankName to SortOrder.ASC)
            .toList()

    override fun countAll(): Long = BankAccount.all().count()
}
