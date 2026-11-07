package org.example.project.features.contract

import kotlinx.datetime.LocalDateTime
import org.example.project.features.contract.data.repository.ContractRepositoryImpl
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The Verträge list's server-side search.
 *
 * Unlike the other lists this one is **not** paged — the endpoint returns every contract — so the
 * search could in principle have been done on the client. It is done here anyway, for the same
 * reason `includeInactive` is: the server owns the predicate, and two places deciding what a list
 * contains is how the two come to disagree.
 */
class ContractSearchTest {
    private val repo = ContractRepositoryImpl()

    /**
     * File-local, like every other fixture in this package. `private` keeps it out of overload
     * resolution for the neighbouring test files, which declare their own `newContract`.
     */
    private fun contract(
        name: String,
        description: String? = null,
        active: Boolean = true,
    ): Contract =
        Contract.new {
            shop = ShopInfo.all().first()
            this.name = name
            this.description = description
            interval = ContractInterval.MONTHLY.name
            dueDay = 1
            startDate = LocalDateTime(2026, 1, 1, 0, 0)
            isActive = active
        }

    @Test
    fun `search matches the name case-insensitively`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                contract("Miete Ladenlokal")
                contract("Strom Stadtwerke")

                assertEquals(listOf("Miete Ladenlokal"), repo.list(search = "miete").map { it.name })
            }
        }
    }

    /** A contract is often found by what it is for, which lives in the description, not the name. */
    @Test
    fun `search matches the description too`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                contract("Vertrag 4711", description = "Kaffeemaschine Wartung")
                contract("Strom Stadtwerke")

                assertEquals(listOf("Vertrag 4711"), repo.list(search = "kaffee").map { it.name })
            }
        }
    }

    @Test
    fun `a blank search is not a filter`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                contract("Miete Ladenlokal")

                assertEquals(1, repo.list(search = "   ").size)
            }
        }
    }

    /** The switch and the box narrow together; neither may quietly drop the other. */
    @Test
    fun `search composes with includeInactive rather than replacing it`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                contract("Miete Ladenlokal")
                contract("Miete Lager", active = false)

                assertEquals(listOf("Miete Ladenlokal"), repo.list(search = "miete").map { it.name })
                assertEquals(
                    listOf("Miete Ladenlokal", "Miete Lager"),
                    repo.list(includeInactive = true, search = "miete").map { it.name },
                )
            }
        }
    }
}
