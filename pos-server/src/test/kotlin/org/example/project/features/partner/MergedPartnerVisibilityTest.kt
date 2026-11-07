package org.example.project.features.partner

import org.example.project.features.partner.data.repository.BusinessPartnerRepositoryImpl
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A merged-away party is invisible to lookups, but still addressable by id.
 *
 * Both halves matter. If the resolver could still find it, an incoming invoice would attach a
 * creditor role to a company the operator has already declared a duplicate. If a read by id
 * 404'd instead of following the pointer, every stored reference to it would break.
 */
@DisplayName("Merged partners")
class MergedPartnerVisibilityTest {
    private val partners = BusinessPartnerRepositoryImpl()
    private val suppliers = SupplierRepositoryImpl()

    @Test
    fun `the resolver's party lookup skips a merged party`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val survivorId =
                transaction {
                    val survivor = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                    val loser = suppliers.create(name = "Metro Cash Carry", vatId = "DE999000111")
                    loser.partner.mergedInto = survivor.partner
                    survivor.partner.id.value
                }

            transaction {
                assertNull(
                    suppliers.findPartner(vatId = "DE999000111", taxNumber = null, name = null),
                    "a merged party must not be resolvable",
                )
                assertEquals(
                    survivorId,
                    suppliers.findPartner(vatId = "DE123456789", taxNumber = null, name = null)?.id?.value,
                )
            }
        }
    }

    /** The tax-number rung is the one nothing else covers, and it is easy to leave behind. */
    @Test
    fun `the tax number rung also skips a merged party`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val survivor = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                val loser = suppliers.create(name = "Metro Cash Carry", taxNumber = "12/345/67890")
                loser.partner.mergedInto = survivor.partner
            }

            transaction {
                assertNull(
                    suppliers.findPartner(vatId = null, taxNumber = "12/345/67890", name = null),
                    "every rung must exclude merged parties, not just the first",
                )
            }
        }
    }

    /** The name rung, likewise. */
    @Test
    fun `the name rung also skips a merged party`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val survivor = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                val loser = suppliers.create(name = "Nordfisch GmbH")
                loser.partner.mergedInto = survivor.partner
            }

            transaction {
                assertNull(
                    suppliers.findPartner(vatId = null, taxNumber = null, name = "Nordfisch GmbH"),
                    "every rung must exclude merged parties, not just the first",
                )
            }
        }
    }

    @Test
    fun `a read addressed to a merged party returns the survivor`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val survivor = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                val loser = suppliers.create(name = "Metro Cash Carry", vatId = "DE999000111")
                loser.partner.mergedInto = survivor.partner

                assertEquals(
                    survivor.partner.id.value,
                    partners.findByIdFollowingMerges(loser.partner.id.value)?.id?.value,
                    "the pointer is followed rather than returning an empty shell",
                )
            }
        }
    }
}
