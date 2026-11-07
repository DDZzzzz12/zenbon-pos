package org.example.project.service

import kotlinx.coroutines.runBlocking
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

/**
 * A3 Task 2: `order.terminal` must be persisted at creation so DSFinV-K `posTerminalId` (and the
 * TSE pending-bill sweeper) can recover order origin. Before this fix the FK was resolved
 * (to derive the TSE `clientId`) but never written back onto `createdOrder`, so it stayed NULL
 * for every order ever created.
 */
@DisplayName("Order.terminal is persisted on creation (DSFinV-K origin)")
class OrderTerminalLinkTest {
    @Test
    fun `createOrder links the resolved terminal onto the order`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OTL_MGR", username = "otl_mgr")
                val category = TestData.createCategory(name = "OTL_Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "OTL_Prod",
                        productNumber = "OTL_P",
                        price = BigDecimal("5.0000"),
                    )
                TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession(tableNumber = "OTL_TBL")
                val deviceId = "otl-device"
                val terminalClientId = "OTL-CLIENT"
                transaction {
                    Terminal.new {
                        this.deviceId = deviceId
                        this.clientId = terminalClientId
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
                                                unitPrice = "5.0",
                                                quantity = 1,
                                                discount = 1.0,
                                            ),
                                        ),
                                ),
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            actorCapabilities = emptySet(),
                        )
                    }

                val persistedClientId =
                    transaction {
                        Orders.findById(response.orderId)!!.terminal?.clientId
                    }

                assertEquals(
                    terminalClientId,
                    persistedClientId,
                    "order.terminal must be set to the terminal resolved via deviceId at creation",
                )
            },
        ) { _ -> }
}
