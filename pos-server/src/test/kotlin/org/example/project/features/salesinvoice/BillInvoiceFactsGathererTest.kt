package org.example.project.features.salesinvoice

import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BillInvoiceFactsGathererTest {
    private val gatherer = BillInvoiceFactsGatherer()

    @Test
    fun `an unknown bill yields null rather than an exception`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                assertNull(gatherer.gather(999_999L))
            }
        }
    }

    @Test
    fun `the seller block is snapshotted from shop_info`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val seller = gatherer.sellerFrom(shop)

                assertEquals(shop.ustIdNr, seller.vatId)
                assertEquals(shop.shopCity, seller.city)
                assertEquals(shop.zipCode.toString(), seller.zip, "zip_code is an integer column")
                assertEquals(shop.shopCountry, seller.country)
            }
        }
    }
}
