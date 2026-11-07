package org.example.project.features.partner

import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The party record: identity that both accounting roles point at. */
@DisplayName("Business partner table")
class BusinessPartnerTableTest {
    @Test
    fun `a party stores identity and starts unmerged`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id =
                transaction {
                    BusinessPartner
                        .new {
                            shop = ShopInfo.all().first()
                            name = "Metro AG"
                            normalizedName = "metro"
                            vatId = "DE123456789"
                            city = "Berlin"
                        }.id.value
                }

            transaction {
                val party = BusinessPartner.findById(id)!!
                assertEquals("Metro AG", party.name)
                assertEquals("DE123456789", party.vatId)
                assertEquals("Berlin", party.city)
                assertNull(party.mergedInto, "a new party is not merged into anything")
            }
        }
    }

    @Test
    fun `normalized_name defaults to empty string and active defaults to true`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id =
                transaction {
                    BusinessPartner
                        .new {
                            shop = ShopInfo.all().first()
                            name = "Kein Normalname GmbH"
                        }.id.value
                }

            transaction {
                val party = BusinessPartner.findById(id)!!
                assertEquals("", party.normalizedName)
                assertTrue(party.isActive)
            }
        }
    }

    @Test
    fun `mergedInto round-trips through a reload in a separate transaction`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val (aId, bId) =
                transaction {
                    val shop = ShopInfo.all().first()
                    val a =
                        BusinessPartner.new {
                            this.shop = shop
                            name = "Metro AG"
                        }
                    val b =
                        BusinessPartner.new {
                            this.shop = shop
                            name = "Metro GmbH (Duplikat)"
                        }
                    b.mergedInto = a
                    a.id.value to b.id.value
                }

            transaction {
                val b = BusinessPartner.findById(bId)!!
                assertEquals(aId, b.mergedInto?.id?.value)
            }
        }
    }
}
