package org.example.project.features.bank

import org.example.project.features.bank.data.repository.BankAccountRepositoryImpl
import org.example.project.features.bank.data.service.BankAccountSeeder
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BankAccountSeederTest {
    private val repo = BankAccountRepositoryImpl()

    private fun seeder() = BankAccountSeeder(repo)

    @Test
    fun `the shop's inline IBAN becomes the first account`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                shop.shopBankIBAN = "DE89 3704 0044 0532 0130 00"
                shop.shopBankName = "Commerzbank"
                shop.shopBankBic = "COBADEFFXXX"

                assertEquals(1, seeder().run())

                val account = BankAccount.all().first()
                assertEquals("DE89370400440532013000", account.iban)
                assertEquals("Commerzbank", account.bankName)
            }
        }
    }

    @Test
    fun `running twice creates nothing new`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                ShopInfo.all().first().shopBankIBAN = "DE89370400440532013000"
                seeder().run()
                assertEquals(0, seeder().run())
                assertEquals(1, BankAccount.all().count())
            }
        }
    }

    @Test
    fun `a shop with no IBAN seeds nothing and is not an error`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                ShopInfo.all().first().shopBankIBAN = null
                assertEquals(0, seeder().run())
                assertEquals(0, BankAccount.all().count())
            }
        }
    }
}
