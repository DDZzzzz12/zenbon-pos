package org.example.project.features.partner

import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.partner.data.repository.BusinessPartnerRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which parties are probably the same company.
 *
 * A suggestion, never an action: the spec is explicit that deciding two accounting identities are
 * one company must not be made by a `WHERE vat_id =` clause. This only gathers the candidates.
 */
@DisplayName("Duplicate suggestions")
class PartnerDuplicateSuggestionTest {
    private val partners = BusinessPartnerRepositoryImpl()
    private val suppliers = SupplierRepositoryImpl()
    private val debtors = DebtorRepositoryImpl()

    @Test
    fun `a creditor and a debtor sharing a VAT id are suggested as one group`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                suppliers.create(name = "Metro AG", vatId = "DE123456789")
                debtors.create(shop = ShopInfo.all().first(), name = "Metro AG", vatId = "DE123456789")
            }

            transaction {
                val groups = partners.findDuplicateGroups(limit = 50)

                assertEquals(1, groups.size, "one company, seen twice, is one group")
                assertEquals(2, groups.single().partners.size)
                assertEquals("DE123456789", groups.single().key)
            }
        }
    }

    /**
     * The name rung matters more than the VAT one in practice: a hand-captured creditor often has no
     * VAT id at all, which is exactly when a duplicate goes unnoticed.
     */
    @Test
    fun `parties sharing a normalized name are suggested even with no VAT id`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                suppliers.create(name = "Getränke-Müller GmbH")
                debtors.create(shop = ShopInfo.all().first(), name = "Getränke Müller")
            }

            transaction {
                val groups = partners.findDuplicateGroups(limit = 50)

                assertEquals(1, groups.size, "the normalizer folds the hyphen and the legal form")
                assertEquals(2, groups.single().partners.size)
            }
        }
    }

    /** Two genuinely different companies must not be offered. */
    @Test
    fun `unrelated parties are not suggested`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                suppliers.create(name = "Metro AG", vatId = "DE123456789")
                suppliers.create(name = "Bäckerei Schmidt", vatId = "DE999999999")
            }

            transaction {
                assertTrue(partners.findDuplicateGroups(limit = 50).isEmpty())
            }
        }
    }
}
