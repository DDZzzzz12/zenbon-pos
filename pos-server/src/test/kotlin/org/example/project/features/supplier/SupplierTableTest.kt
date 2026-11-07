package org.example.project.features.supplier

import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.features.supplier.domain.models.SupplierBankAccount
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupplierTableTest {
    @Test
    fun `a supplier persists with its known bank accounts`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val supplier =
                    Supplier.new {
                        partner = party("Metro AG", vatId = "DE123456789")
                        creditorNo = "70001"
                        isProvisional = false
                    }
                SupplierBankAccount.new {
                    this.partner = supplier.partner
                    iban = "DE89370400440532013000"
                    verified = true
                }

                val reloaded = Supplier.findById(supplier.id.value)!!
                // Identity is read off the party: the creditor role has no name or VAT ID of its
                // own any more. The intent of the assertion is unchanged — the values written are
                // the values that come back — only their home has moved.
                assertEquals("Metro AG", reloaded.partner.name)
                assertEquals("DE123456789", reloaded.partner.vatId)
                assertEquals("70001", reloaded.creditorNo)
                assertFalse(reloaded.isProvisional)
                assertEquals(1, reloaded.partner.bankAccounts.count())
                assertTrue(
                    reloaded.partner.bankAccounts
                        .first()
                        .verified,
                )
            }
        }
    }

    /**
     * The uniqueness that replaced `supplier_vat_id_unique`: **one creditor role per company**.
     *
     * It is deliberately not a unique index on `business_partner.vat_id` — a creditor and a debtor
     * that share a VAT ID stay two parties until a human merges them, and a unique index there would
     * make that merge unreachable (see `PartnerPerRoleTest`). The `SUPPLIER_VAT_ID_TAKEN` 409 the
     * create dialog renders lives in `SupplierServiceImpl.requireVatIdFree` instead.
     *
     * The second half of the old test survives in a new home: any number of *provisional* creditors
     * may exist without a VAT ID, each on its own party, which is what the resolver relies on when a
     * scan carries only a name.
     */
    @Test
    fun `a company carries at most one creditor role, yet any number may have no VAT ID`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                Supplier.new {
                    partner = party("Bäckerei Nord")
                    isProvisional = true
                }
                Supplier.new {
                    partner = party("Bäckerei Süd")
                    isProvisional = true
                }
            }
            transaction {
                assertEquals(2, Supplier.all().toList().count { it.partner.vatId == null })
            }

            val metro = transaction { party("Metro AG", vatId = "DE111111111").id.value }
            transaction { Supplier.new { partner = BusinessPartner.findById(metro)!! } }
            assertFailsWith<ExposedSQLException> {
                transaction {
                    Supplier.new { partner = BusinessPartner.findById(metro)!! }
                }
            }
        }
    }

    @Test
    fun `is_provisional defaults to false and active defaults to true`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val supplier =
                    Supplier.new {
                        partner = party("Getränke Müller GmbH")
                    }
                assertFalse(supplier.isProvisional)
                assertTrue(supplier.isActive)
            }
        }
    }

    /**
     * The party a role row points at, and now the only place its identity lives. `partner_id` is
     * non-null, so no `Supplier.new` here can stand on its own.
     */
    private fun party(
        name: String,
        vatId: String? = null,
    ): BusinessPartner =
        BusinessPartner.new {
            this.shop = ShopInfo.all().first()
            this.name = name
            this.vatId = vatId
        }
}
