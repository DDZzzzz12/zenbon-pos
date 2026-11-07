package org.example.project.features.partner

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierServiceImpl
import org.example.project.features.supplier.domain.repository.SupplierSort
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Search and ordering reach the party through the join, and read the name the party carries **now**.
 *
 * The business-partner migration left `business_partner.name` as the only name there is, so "which column?" is no longer the
 * question. What is still worth proving is that the list query joins at all and re-reads the party
 * on every call: each creditor here is created under one name and its party renamed afterwards, so
 * a query that answered from the name supplied at creation — a cached row, a denormalised copy
 * reintroduced later — would miss the search term and return the ordering back to front.
 */
@DisplayName("Creditor search and sort read the party's current name")
class PartnerSearchTest {
    private val repository = SupplierRepositoryImpl()
    private val service = SupplierServiceImpl(repository, ShopConfigurationManager)

    @Test
    fun `a term matches the party's current name, not the one it was created with`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val supplier = repository.create(name = "Alt GmbH", vatId = "DE100000001")
                supplier.partner.name = "Frischemarkt GmbH"
            }

            val found =
                runBlocking {
                    service.list(
                        provisionalOnly = false,
                        search = "Frischemarkt",
                        sort = SupplierSort.NAME,
                        ascending = true,
                        limit = 200,
                        offset = 0,
                    )
                }

            assertEquals(listOf("Frischemarkt GmbH"), found.items.map { it.name })
        }
    }

    /** The ordering key moves with the search: it is the party's name that decides the sequence. */
    @Test
    fun `the name ordering follows the party's current name, not the insertion order`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                // The creditors are inserted in A-then-B order and their parties renamed to sort
                // the other way round, so an ordering that fell back to insertion order — or to a
                // name captured at creation — would return them reversed.
                repository.create(name = "Alpha GmbH", vatId = "DE100000002").partner.name = "Zeta GmbH"
                repository.create(name = "Beta GmbH", vatId = "DE100000003").partner.name = "Anton GmbH"
            }

            val listed =
                runBlocking {
                    service.list(
                        provisionalOnly = false,
                        search = null,
                        sort = SupplierSort.NAME,
                        ascending = true,
                        limit = 200,
                        offset = 0,
                    )
                }

            assertEquals(listOf("Anton GmbH", "Zeta GmbH"), listed.items.map { it.name })
        }
    }
}
