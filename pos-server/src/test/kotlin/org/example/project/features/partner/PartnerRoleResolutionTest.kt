package org.example.project.features.partner

import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierResolver
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An invoice from a company we already sell to must add a creditor **role**, not a second company.
 *
 * This is the payoff of the whole model: before it, the resolver matched only suppliers, so the
 * customer's identity was invisible to it and a duplicate was the guaranteed outcome.
 *
 * Both debtors here are created through [DebtorRepositoryImpl.create] rather than by hand, because
 * the thing under test is precisely whether the party the *production* debtor path writes carries a
 * comparison key the resolver can find. A hand-built party with a hand-written `normalized_name`
 * would prove nothing — that exact mismatch was a live bug earlier in this work.
 */
@DisplayName("Resolver attaches a role to an existing party")
class PartnerRoleResolutionTest {
    private val suppliers = SupplierRepositoryImpl()
    private val debtors = DebtorRepositoryImpl()
    private val documents = DocumentRepositoryImpl()
    private val resolver = SupplierResolver(suppliers)

    @Test
    fun `an invoice from an existing customer creates a creditor role, not a new party`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                // Metro exists only as a customer today.
                val debtor =
                    debtors.create(
                        shop = ShopInfo.all().first(),
                        name = "Metro AG",
                        vatId = "DE123456789",
                    )
                val party = debtor.partner

                val invoice =
                    documents.createInvoice(sellerName = "Metro AG", sellerVatId = "DE123456789")

                val resolution = resolver.resolveAndLink(invoice)

                assertEquals(party.id, resolution.supplier!!.partner.id, "the role must join the existing party")
                assertEquals(1, BusinessPartner.all().count().toInt(), "no second company may be created")
                assertTrue(resolution.created, "the creditor role itself is new")
            }
        }
    }

    /**
     * The name rung, on its own.
     *
     * The VAT-id test above would still pass with the name lookup broken, and the name rung is the
     * fragile one: it compares against `normalized_name`, which strips legal forms, so "Metro AG"
     * has to have been stored as "metro" by the debtor path for this to find anything.
     */
    @Test
    fun `an invoice naming an existing customer with no VAT id still finds the party`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val debtor =
                    debtors.create(
                        shop = ShopInfo.all().first(),
                        name = "Getränke Müller GmbH",
                    )
                val party = debtor.partner

                // No VAT id and no tax number: only the name rung can match.
                val invoice = documents.createInvoice(sellerName = "getränke müller")

                val resolution = resolver.resolveAndLink(invoice)

                assertEquals(party.id, resolution.supplier!!.partner.id, "the name rung must reach the party")
                assertEquals(1, BusinessPartner.all().count().toInt(), "no second company may be created")
                assertTrue(resolution.created, "the creditor role itself is new")
            }
        }
    }
}
