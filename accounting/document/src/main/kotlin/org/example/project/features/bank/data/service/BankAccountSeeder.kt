package org.example.project.features.bank.data.service

import org.example.project.features.bank.domain.repository.BankAccountRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.slf4j.LoggerFactory

/**
 * Creates the first [org.example.project.features.bank.domain.models.BankAccount] from the account
 * `shop_info` already holds inline, so an existing installation arrives with its own account known
 * rather than an empty registry.
 *
 * One-shot and idempotent — an IBAN that already resolves is skipped, so this is safe on every
 * start. It is a **one-way seed**: `shop_info`'s columns keep their own life as the setup form's
 * fields and are not read anywhere else.
 *
 * Returns the number of accounts created. Requires an ambient transaction.
 */
class BankAccountSeeder(
    private val accounts: BankAccountRepository,
) {
    private val logger = LoggerFactory.getLogger(BankAccountSeeder::class.java)

    fun run(): Int {
        var created = 0
        ShopInfo.all().toList().forEach { shop ->
            val iban = shop.shopBankIBAN?.takeIf { it.isNotBlank() } ?: return@forEach
            if (accounts.findByIban(iban) != null) return@forEach

            accounts.create(
                shop = shop,
                iban = iban,
                bic = shop.shopBankBic,
                bankName = shop.shopBankName,
                accountHolder = shop.companyName ?: shop.shopName,
            )
            created++
        }
        if (created > 0) logger.info("Seeded {} bank account(s) from shop_info", created)
        return created
    }
}
