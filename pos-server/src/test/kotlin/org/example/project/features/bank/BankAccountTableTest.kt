package org.example.project.features.bank

import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BankAccountTableTest {
    @Test
    fun `an account persists against its shop`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val account =
                    BankAccount.new {
                        this.shop = shop
                        iban = "DE89370400440532013000"
                        bic = "COBADEFFXXX"
                        bankName = "Commerzbank"
                        accountHolder = "Zenbon GmbH"
                        ledgerAccount = "1200"
                    }

                val reloaded = BankAccount.findById(account.id.value)!!
                assertEquals("DE89370400440532013000", reloaded.iban)
                assertEquals("1200", reloaded.ledgerAccount)
                assertEquals("EUR", reloaded.currency)
                assertTrue(reloaded.isActive)
            }
        }
    }

    /**
     * The two `BankAccount.new` calls are in **separate** transactions on purpose: within one,
     * Exposed's entity cache can defer the insert past the assertion, so the constraint would not
     * fire where you expect.
     */
    @Test
    fun `iban is unique`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                BankAccount.new {
                    this.shop = shop
                    iban = "DE89370400440532013000"
                }
            }
            assertFailsWith<ExposedSQLException> {
                transaction {
                    val shop = ShopInfo.all().first()
                    BankAccount.new {
                        this.shop = shop
                        iban = "DE89370400440532013000"
                    }
                }
            }
            Unit
        }
    }
}
