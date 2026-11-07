package org.example.project.features.debtor

import org.example.project.features.customer.database.tables.CustomerTable
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebtorTableTest {
    @Test
    fun `a debtor round-trips with its optional fields left null`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val debtor =
                    Debtor.new {
                        this.partner = party(shop, "Musterfirma GmbH")
                        this.shop = shop
                    }

                // Identity is read off the party; the role keeps only its own columns.
                assertEquals("Musterfirma GmbH", debtor.partner.name)
                assertNull(debtor.debtorNo, "debtor_no starts null — the Steuerberater owns that namespace")
                assertNull(debtor.customerId, "a walk-in company needs no loyalty record")
                assertNull(debtor.partner.vatId)
                assertTrue(debtor.isActive, "a new debtor is active")
            }
        }
    }

    @Test
    fun `debtor_no is optional but unique once set`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                Debtor.new {
                    this.partner = party(shop, "Erste GmbH")
                    this.shop = shop
                    this.debtorNo = "10247"
                }
                Debtor.new {
                    this.partner = party(shop, "Zweite GmbH")
                    this.shop = shop
                }

                assertEquals(
                    listOf("10247", null),
                    Debtor.all().sortedBy { it.partner.name }.map { it.debtorNo },
                )
            }
        }
    }

    @Test
    fun `a debtor can be linked to a loyalty customer`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val customerId =
                    CustomerTable.insertAndGetId {
                        it[customerNumber] = "K-0001"
                        it[firstName] = "Anna"
                        it[lastName] = "Muster"
                        it[updatedAt] = currentTimeStamp()
                    }

                val debtor =
                    Debtor.new {
                        this.partner = party(shop, "Anna Muster")
                        this.shop = shop
                        this.customerId = customerId
                    }

                assertEquals(customerId, debtor.customerId)
            }
        }
    }

    /**
     * The party a role row points at, and now the only place its identity lives. `partner_id` is
     * non-null, so no `Debtor.new` here can stand on its own.
     */
    private fun party(
        shop: ShopInfo,
        name: String,
    ): BusinessPartner =
        BusinessPartner.new {
            this.shop = shop
            this.name = name
        }
}
