package org.example.project.features.partner

import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.normalizeSupplierName
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The party's `normalized_name` is computed **one** way, no matter which role created the party.
 *
 * The resolver looks a company up with `normalizedName eq normalizeSupplierName(sellerName)`. A
 * party written with a different key is invisible to that lookup, and the resolver then creates a
 * second party for a company we already have — silently, in the exact case the business-partner
 * model exists for: an invoice arriving from a firm we already sell to.
 */
@DisplayName("Party normalized name is creditor-normalized on every path")
class PartnerNormalizedNameTest {
    private val suppliers = SupplierRepositoryImpl()
    private val debtors = DebtorRepositoryImpl()

    @Test
    fun `a debtor-created party is findable by the key the resolver computes`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                debtors.create(shop = ShopInfo.all().first(), name = "Metro AG")
            }

            // Reloaded in its own transaction: the in-memory entity would prove only that a field
            // was assigned, not that the row a lookup reads carries the key.
            transaction {
                val party = Debtor.all().first().partner
                assertEquals(
                    normalizeSupplierName("Metro AG"),
                    party.normalizedName,
                    "the resolver searches for this key; a debtor-created party must carry it",
                )
            }
        }
    }

    @Test
    fun `the creditor and debtor paths produce the same key for the same company`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                suppliers.create(name = "Metro AG")
                debtors.create(shop = ShopInfo.all().first(), name = "Metro AG")
            }

            transaction {
                val creditorParty = Supplier.all().first().partner
                val debtorParty = Debtor.all().first().partner
                assertEquals(
                    creditorParty.normalizedName,
                    debtorParty.normalizedName,
                    "one company, one comparison key — whichever role happened to create the party",
                )
            }
        }
    }
}
