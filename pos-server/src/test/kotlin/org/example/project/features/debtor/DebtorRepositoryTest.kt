package org.example.project.features.debtor

import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebtorRepositoryTest {
    private val repo = DebtorRepositoryImpl()

    @Test
    fun `create trims blanks to null rather than storing empty strings`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val debtor = repo.create(shop = shop, name = "Musterfirma GmbH", vatId = "   ", city = "")

                assertNull(debtor.partner.vatId, "a blank VAT id is absent, not empty")
                assertNull(debtor.partner.city)
            }
        }
    }

    @Test
    fun `findByVatId ignores spacing and case`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                repo.create(shop = shop, name = "Musterfirma GmbH", vatId = "DE123456789")

                assertEquals("Musterfirma GmbH", repo.findByVatId("de 123 456 789")?.partner?.name)
                assertNull(repo.findByVatId("DE999999999"))
                assertNull(repo.findByVatId(null))
            }
        }
    }

    @Test
    fun `findByDebtorNo finds an assigned account and nothing for an unassigned one`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                repo.create(shop = shop, name = "Mit Konto", debtorNo = "10247")
                repo.create(shop = shop, name = "Ohne Konto")

                assertEquals("Mit Konto", repo.findByDebtorNo("10247")?.partner?.name)
                assertNull(repo.findByDebtorNo(null))
            }
        }
    }

    @Test
    fun `listActive excludes deactivated debtors and sorts by name`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                repo.create(shop = shop, name = "Zeta GmbH")
                repo.create(shop = shop, name = "Alpha GmbH")
                repo.create(shop = shop, name = "Geschlossen GmbH").isActive = false

                assertEquals(listOf("Alpha GmbH", "Zeta GmbH"), repo.listActive().map { it.partner.name })
            }
        }
    }

    @Test
    fun `debtorNoIsTaken reports an assigned account so a generator never reuses it`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                repo.create(shop = shop, name = "Mit Konto", debtorNo = "10247")

                assertTrue(repo.debtorNoIsTaken("10247"))
                assertFalse(repo.debtorNoIsTaken("10248"))
            }
        }
    }
}
