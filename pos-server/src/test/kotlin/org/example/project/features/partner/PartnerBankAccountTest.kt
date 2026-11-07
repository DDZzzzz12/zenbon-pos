package org.example.project.features.partner

import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * An IBAN belongs to the company, not to one direction of trade.
 *
 * Registering it through the creditor role stores it on the party, which is what lets the debtor
 * side eventually see it — debtors have no IBAN of their own at all today.
 */
@DisplayName("Partner bank accounts")
class PartnerBankAccountTest {
    private val suppliers = SupplierRepositoryImpl()

    @Test
    fun `an IBAN registered via the creditor role is held by the party`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val supplier = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                suppliers.registerBankAccount(supplier, "DE21500500009876543210", bic = null, invoiceId = null)

                val party = supplier.partner
                assertEquals(
                    listOf("DE21500500009876543210"),
                    party.bankAccounts.map { it.iban },
                )
            }
        }
    }
}
