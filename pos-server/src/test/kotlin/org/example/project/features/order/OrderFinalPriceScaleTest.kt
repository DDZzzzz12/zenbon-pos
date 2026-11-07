package org.example.project.features.order

import kotlinx.coroutines.runBlocking
import org.example.project.core.auth.PriceAuthority
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TSER-5 / INV-F7: `finalPrice = (unitPrice + extrasDelta) * discount` must be normalized to
 * scale 2 at creation. A scale-4 residue (e.g. x.xx50) makes the signed Kassenbeleg buckets
 * (truncating per Anhang I) fall a cent below the payment sum, and the HALF_UP-printed Beleg
 * disagrees with the signed bucket. Fixing at the source (2dp) keeps buckets, VAT split and
 * print all in agreement to the cent.
 */
@DisplayName("Order finalPrice is stored at scale 2 (TSER-5)")
class OrderFinalPriceScaleTest {
    @Test
    fun `half-off 9_99 item stores finalPrice 5_00 at scale 2, not 4_9950`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "FPS_MGR", username = "fps_mgr")
                val category = TestData.createCategory(name = "FPS_Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "FPS_Prod",
                        productNumber = "FPS_P",
                        price = BigDecimal("9.9900"),
                    )
                TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession(tableNumber = "FPS_TBL")
                val deviceId = "fps-device"
                transaction {
                    Terminal.new {
                        this.deviceId = deviceId
                        this.clientId = "FPS-CLIENT"
                        this.isActive = true
                        this.registeredAt = currentTimeStamp()
                        this.lastSeenAt = currentTimeStamp()
                    }
                }

                val service = GlobalContext.get().get<OrderService>()
                val response =
                    runBlocking {
                        service.createOrder(
                            request =
                                OrderDto.CreateOrderRequestDto(
                                    tableInfoId = tws.session.id.value,
                                    tableId = tws.table.id.value,
                                    orderItems =
                                        listOf(
                                            OrderItemDto.CreateOrderItemRequestDto(
                                                productId = product.id.value,
                                                unitPrice = "9.99",
                                                quantity = 1,
                                                discount = 0.5,
                                            ),
                                        ),
                                ),
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            actorCapabilities = setOf(PriceAuthority.STEEP_DISCOUNT_AUTHORITY),
                        )
                    }

                val finalPrice =
                    transaction {
                        Orders
                            .findById(response.orderId)!!
                            .items
                            .single()
                            .finalPrice
                    }

                // (9.9900 * 0.50) = 4.9950 pre-fix (a half-cent residue); normalized to 5.0000 at
                // creation. The column is DECIMAL(19,4), so read-back scale is always 4 — the fix is
                // proven by the STORED VALUE carrying no sub-cent part: stripTrailingZeros().scale()
                // is 0 for 5.0000 but 3 for the pre-fix 4.9950.
                assertTrue(
                    finalPrice.stripTrailingZeros().scale() <= 2,
                    "finalPrice must carry no sub-cent residue, was $finalPrice",
                )
                assertEquals(0, BigDecimal("5.00").compareTo(finalPrice), "9.99 * 0.5 rounds HALF_UP to 5.00")
            },
        ) { _ -> }
}
