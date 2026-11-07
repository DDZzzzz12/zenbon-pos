package org.example.project.features.zBon

import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.product.domain.models.Product
import org.example.project.features.zBon.data.service.ZBonPfandAggregator
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZBonPfandAggregatorTest {
    @BeforeEach fun setup() {
        TestDatabase.setup()
    }

    @AfterEach fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `buckets deposit and return by vat rate, ignores plain products, suppresses zero`() {
        transaction {
            TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates() // [0]=19%, [1]=7%
            val cat = TestData.createCategory("C")
            val drink = TestData.createProduct(cat.id.value, "Cola", "COLA")
            val pfand = TestData.createProduct(cat.id.value, "Pfand Einweg", "PF25").apply { isDeposit = true }
            val leergut = TestData.createProduct(cat.id.value, "Leergut Einweg", "LG25").apply { isDepositReturn = true }
            val tableWithSession = TestData.createTableWithSession()
            val session = tableWithSession.session

            fun item(
                p: Product,
                price: String,
                vatIdx: Int,
            ) = OrderItem.new {
                this.session = session
                this.product = p
                this.unitPrice = BigDecimal(price)
                this.finalPrice = BigDecimal(price)
                this.vat = vats[vatIdx]
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }

            val items =
                listOf(
                    item(drink, "2.00", 0),
                    item(pfand, "0.25", 0),
                    item(pfand, "0.25", 0),
                    item(leergut, "-0.25", 0),
                    item(pfand, "0.15", 1), // 7% deposit (unusual but valid)
                )

            val agg = ZBonPfandAggregator.aggregate(items)
            assertEquals(0, BigDecimal("0.50").compareTo(agg.getValue("19").deposit))
            assertEquals(0, BigDecimal("-0.25").compareTo(agg.getValue("19").returned))
            assertEquals(0, BigDecimal("0.15").compareTo(agg.getValue("7").deposit))
            assertEquals(0, BigDecimal.ZERO.compareTo(agg.getValue("7").returned))
            assertNull(agg["0"]) // no 0% pfand
        }
    }
}
