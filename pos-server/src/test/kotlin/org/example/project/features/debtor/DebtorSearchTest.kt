package org.example.project.features.debtor

import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The Debitoren list's server-side search.
 *
 * Server-side for the same reason the active filter is: the list is paged, so a filter applied to a
 * fetched page would silently hide every match beyond it.
 */
class DebtorSearchTest {
    private val repo = DebtorRepositoryImpl()

    private fun shop() = ShopInfo.all().first()

    @Test
    fun `search matches the name case-insensitively`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                repo.create(shop = shop(), name = "Acme GmbH", city = "Berlin")
                repo.create(shop = shop(), name = "Beta Catering AG", city = "Hamburg")

                assertEquals(listOf("Acme GmbH"), repo.list(search = "acme").map { it.partner.name })
            }
        }
    }

    @Test
    fun `search matches the city, the VAT id and the debtor number`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val acme = repo.create(shop = shop(), name = "Acme GmbH", city = "Berlin", vatId = "DE900000001")
                acme.debtorNo = "10001"
                repo.create(shop = shop(), name = "Beta Catering AG", city = "Hamburg")

                assertEquals(listOf("Acme GmbH"), repo.list(search = "berlin").map { it.partner.name })
                assertEquals(listOf("Acme GmbH"), repo.list(search = "DE9000").map { it.partner.name })
                assertEquals(listOf("Acme GmbH"), repo.list(search = "10001").map { it.partner.name })
            }
        }
    }

    /** A blank box must widen the list back, not empty it. */
    @Test
    fun `a blank search is not a filter`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                repo.create(shop = shop(), name = "Acme GmbH")

                assertEquals(1, repo.list(search = "   ").size)
                assertEquals(1, repo.count(search = "   "))
            }
        }
    }

    @Test
    fun `search composes with the active filter rather than replacing it`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                repo.create(shop = shop(), name = "Acme GmbH")
                repo.create(shop = shop(), name = "Acme Alt GmbH").isActive = false

                val hits = repo.list(activeOnly = true, search = "acme")
                assertEquals(listOf("Acme GmbH"), hits.map { it.partner.name })
            }
        }
    }

    @Test
    fun `count honours the same search`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                repo.create(shop = shop(), name = "Acme GmbH")
                repo.create(shop = shop(), name = "Beta Catering AG")

                assertEquals(2, repo.count())
                assertEquals(1, repo.count(search = "acme"))
            }
        }
    }
}
