package org.example.project.features.bank.domain.repository

import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.setup.domain.models.ShopInfo

/**
 * Our own bank accounts.
 *
 * **Every method requires an ambient transaction.** IBAN arguments may be in any spacing or case —
 * implementations canonicalise before comparing, because banks print groups of four and CAMT does
 * not.
 */
interface BankAccountRepository {
    fun create(
        shop: ShopInfo,
        iban: String,
        bic: String?,
        bankName: String?,
        accountHolder: String? = null,
        ledgerAccount: String? = null,
        currency: String = "EUR",
    ): BankAccount

    fun findById(id: Long): BankAccount?

    /** Null when [iban] is null, blank, or matches nothing. Spacing and case are ignored. */
    fun findByIban(iban: String?): BankAccount?

    fun listActive(): List<BankAccount>

    fun countAll(): Long
}
