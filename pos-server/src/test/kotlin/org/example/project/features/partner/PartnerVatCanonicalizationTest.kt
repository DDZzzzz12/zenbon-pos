package org.example.project.features.partner

import org.example.project.core.utils.VatIdFormat
import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierResolver
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The party's `vat_id` is computed **one** way, no matter which role created the party.
 *
 * The resolver's first and most trusted rung matches `business_partner.vat_id` with an exact `eq`.
 * A VAT id is therefore a comparison key, and a key written by two paths that compute it
 * differently is a key that fails to compare. A creditor captured by hand as "DE 123 456 789" was
 * stored verbatim, so the next invoice asserting "DE123456789" — the form a structured e-invoice
 * carries — found nothing and minted a second company for a firm we already had.
 *
 * This is the second instance of the family: `normalized_name` had the identical split and is
 * covered by [PartnerNormalizedNameTest].
 */
@DisplayName("Party VAT id is canonicalized on every path")
class PartnerVatCanonicalizationTest {
    private val suppliers = SupplierRepositoryImpl()
    private val debtors = DebtorRepositoryImpl()
    private val documents = DocumentRepositoryImpl()
    private val resolver = SupplierResolver(suppliers)

    /**
     * The actual failure, end to end: a hand-captured creditor with a spaced VAT id, then an
     * invoice asserting the canonical form.
     */
    @Test
    fun `an invoice finds a hand-captured creditor whose VAT id was typed with spaces`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val supplier = suppliers.create(name = "Metro AG", vatId = "de 123 456 789")
                val party = supplier.partner

                // A deliberately different seller name: the name rung would otherwise rescue the
                // lookup and the test would pass with the VAT rung entirely broken.
                val invoice =
                    documents.createInvoice(sellerName = "Metro Handel", sellerVatId = "DE123456789")

                val resolution = resolver.resolveAndLink(invoice)

                assertEquals(party.id, resolution.supplier!!.partner.id, "the invoice must reach the existing company")
                assertEquals(1, BusinessPartner.all().count().toInt(), "no second company may be created")
            }
        }
    }

    /** The mirror: stored canonical, asserted with spaces. The lookup side has to canonicalize too. */
    @Test
    fun `an invoice asserting a spaced VAT id finds a party stored canonically`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val supplier = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                val party = supplier.partner

                val invoice =
                    documents.createInvoice(sellerName = "Metro Handel", sellerVatId = "de 123 456 789")

                val resolution = resolver.resolveAndLink(invoice)

                assertEquals(party.id, resolution.supplier!!.partner.id, "the search key must be canonicalized too")
                assertEquals(1, BusinessPartner.all().count().toInt(), "no second company may be created")
            }
        }
    }

    /**
     * The invariant that stops this recurring: both role paths write the same key for one company.
     */
    @Test
    fun `the creditor and debtor paths store the same VAT key for the same company`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                suppliers.create(name = "Metro AG", vatId = "de 123 456 789")
                debtors.create(shop = ShopInfo.all().first(), name = "Metro AG", vatId = "de 123 456 789")
            }

            // Reloaded in a fresh transaction: an in-memory entity would prove only that a field
            // was assigned, not that the row a lookup reads carries the key.
            transaction {
                val creditorParty = Supplier.all().first().partner
                val debtorParty = Debtor.all().first().partner
                assertEquals(
                    VatIdFormat.canonical("de 123 456 789"),
                    creditorParty.vatId,
                    "the creditor path must store the canonical key the resolver searches for",
                )
                assertEquals(
                    creditorParty.vatId,
                    debtorParty.vatId,
                    "one company, one comparison key — whichever role happened to create the party",
                )
            }
        }
    }
}
