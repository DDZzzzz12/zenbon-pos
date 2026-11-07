package org.example.project.features.partner

import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Every role row has a party, and the party carries the identity.
 *
 * Creating a creditor and a debtor that share a VAT id produces **two** parties, deliberately.
 * Deciding that two accounting identities are one company is a human judgement with fiscal reach —
 * it belongs in the merge feature (stage 3), not in a create path guessing from a VAT id.
 */
@DisplayName("One party per role")
class PartnerPerRoleTest {
    private val suppliers = SupplierRepositoryImpl()
    private val debtors = DebtorRepositoryImpl()

    @Test
    fun `a supplier and a debtor sharing a VAT id get two parties, not one`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            // Both go through their repositories rather than `Entity.new`: `partner_id` is non-null
            // as of the previous task, so a bare `Debtor.new { }` cannot satisfy the schema — and
            // routing through the repository is what this test is actually about.
            transaction {
                suppliers.create(name = "Metro AG", vatId = "DE123456789")
                debtors.create(
                    shop = ShopInfo.all().first(),
                    name = "Metro AG",
                    vatId = "DE123456789",
                )
            }

            transaction {
                val supplierParty = Supplier.all().first().partner
                val debtorParty = Debtor.all().first().partner
                assertEquals("Metro AG", supplierParty.name)
                assertEquals("Metro AG", debtorParty.name)
                assertEquals(
                    2,
                    listOf(supplierParty.id.value, debtorParty.id.value).distinct().size,
                    "nothing may merge them automatically — that is a human decision",
                )
            }
        }
    }
}
