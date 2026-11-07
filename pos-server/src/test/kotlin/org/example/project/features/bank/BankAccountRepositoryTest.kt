package org.example.project.features.bank

import org.example.project.features.bank.data.repository.BankAccountRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BankAccountRepositoryTest {
    private val repo = BankAccountRepositoryImpl()

    @Test
    fun `findByIban normalises spacing and case`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                repo.create(shop = shop, iban = "DE89370400440532013000", bic = null, bankName = "Commerzbank")

                assertEquals("DE89370400440532013000", repo.findByIban("de89 3704 0044 0532 0130 00")?.iban)
                assertNull(repo.findByIban("DE00000000000000000000"))
                assertNull(repo.findByIban(null))
                assertNull(repo.findByIban("   "))
            }
        }
    }

    @Test
    fun `create stores the IBAN in canonical form`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val account = repo.create(shop = shop, iban = "de89 3704 0044 0532 0130 00", bic = null, bankName = null)
                assertEquals("DE89370400440532013000", account.iban)
            }
        }
    }

    @Test
    fun `listActive excludes deactivated accounts`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                repo.create(shop = shop, iban = "DE89370400440532013000", bic = null, bankName = null)
                val closed = repo.create(shop = shop, iban = "DE02120300000000202051", bic = null, bankName = null)
                closed.isActive = false

                assertEquals(listOf("DE89370400440532013000"), repo.listActive().map { it.iban })
            }
        }
    }
}
