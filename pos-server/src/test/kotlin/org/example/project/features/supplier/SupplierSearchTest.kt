package org.example.project.features.supplier

import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.domain.repository.SupplierSort
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The creditor list's server-side search and ordering.
 *
 * Both are server-side rather than client-side for the reason `SuppliersViewModel` already gives
 * for the provisional filter: the list is paged, so a client-side filter would silently hide every
 * match beyond the current page.
 */
class SupplierSearchTest {
    private val repo = SupplierRepositoryImpl()

    @Test
    fun `search matches the name case-insensitively`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                repo.create(name = "Getränke Müller GmbH", vatId = "DE111111111")
                repo.create(name = "Metro AG", vatId = "DE123456789")

                assertEquals(listOf("Getränke Müller GmbH"), repo.list(search = "müller").map { it.partner.name })
            }
        }
    }

    @Test
    fun `search matches the vat id and the creditor number`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val metro = repo.create(name = "Metro AG", vatId = "DE123456789")
                metro.creditorNo = "70001"
                repo.create(name = "Getränke Müller GmbH", vatId = "DE111111111")

                assertEquals(listOf("Metro AG"), repo.list(search = "de1234").map { it.partner.name })
                assertEquals(listOf("Metro AG"), repo.list(search = "70001").map { it.partner.name })
            }
        }
    }

    @Test
    fun `count honours the same search`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                repo.create(name = "Metro AG", vatId = "DE123456789")
                repo.create(name = "Getränke Müller GmbH", vatId = "DE111111111")

                assertEquals(2, repo.count())
                assertEquals(1, repo.count(search = "metro"))
            }
        }
    }

    /** A blank box must not empty the list — the trim-to-null is what stops that. */
    @Test
    fun `a blank search is not a filter`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                repo.create(name = "Metro AG", vatId = "DE123456789")

                assertEquals(1, repo.list(search = "   ").size)
                assertEquals(1, repo.count(search = "   "))
            }
        }
    }

    @Test
    fun `search composes with the provisional filter rather than replacing it`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                repo.create(name = "Metro AG", vatId = "DE123456789", isProvisional = false)
                repo.create(name = "Metro Provisorisch", vatId = null, isProvisional = true)

                val hits = repo.list(provisionalOnly = true, search = "metro")
                assertEquals(listOf("Metro Provisorisch"), hits.map { it.partner.name })
            }
        }
    }

    @Test
    fun `sort orders by the named column in the named direction`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                repo.create(name = "Metro AG", vatId = "DE123456789")
                repo.create(name = "Aldi Nord", vatId = "DE222222222")

                assertEquals(
                    listOf("Aldi Nord", "Metro AG"),
                    repo.list(sort = SupplierSort.NAME, ascending = true).map { it.partner.name },
                )
                assertEquals(
                    listOf("Metro AG", "Aldi Nord"),
                    repo.list(sort = SupplierSort.NAME, ascending = false).map { it.partner.name },
                )
            }
        }
    }

    @Test
    fun `sort by creditor number is a different order than by name`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                repo.create(name = "Aldi Nord", vatId = "DE222222222").creditorNo = "70009"
                repo.create(name = "Metro AG", vatId = "DE123456789").creditorNo = "70001"

                assertEquals(
                    listOf("Metro AG", "Aldi Nord"),
                    repo.list(sort = SupplierSort.CREDITOR_NO, ascending = true).map { it.partner.name },
                )
            }
        }
    }

    /** An unknown or absent `sort` parameter must not 400 — it falls back to the default order. */
    @Test
    fun `parse falls back to NAME`() {
        assertEquals(SupplierSort.NAME, SupplierSort.parse(null))
        assertEquals(SupplierSort.NAME, SupplierSort.parse("nonsense"))
        assertEquals(SupplierSort.VAT_ID, SupplierSort.parse("vat_id"))
        assertTrue(SupplierSort.entries.isNotEmpty())
    }
}
