package org.example.project.features.rotation

import kotlinx.coroutines.test.runTest
import org.example.project.core.data.domain.impl.service.ClientOrderServiceImpl
import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.data.remote.OrderItemApiClient
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.order.ItemStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The till sends who its label showed when the free table was opened (captured by the order screen, see
 * TableOrderViewModelRotationToastTest), so the server can tell when the rotation moved on. The service only
 * forwards it; it never reads the live store itself.
 */
class RotationExpectationTest {
    @Test
    fun sendsTheGivenExpectation() =
        runTest {
            val api = CapturingOrderApiClient()
            val service = ClientOrderServiceImpl(api, UnusedOrderItemApiClient())

            service.submitOrder(sessionId = null, items = emptyList(), tableId = 3L, expectedNextEmployeeId = "id-anna")

            assertEquals("id-anna", api.requests.single().expectedNextEmployeeId)
        }

    @Test
    fun omitsExpectationWhenOwnerIsNamed() =
        runTest {
            val api = CapturingOrderApiClient()
            val service = ClientOrderServiceImpl(api, UnusedOrderItemApiClient())

            service.submitOrder(
                sessionId = null,
                items = emptyList(),
                tableId = 3L,
                forEmployeeNumber = "E9",
                expectedNextEmployeeId = "id-anna",
            )

            assertNull(api.requests.single().expectedNextEmployeeId)
        }

    @Test
    fun omitsExpectationWhenNoneWasCaptured() =
        runTest {
            val api = CapturingOrderApiClient()
            val service = ClientOrderServiceImpl(api, UnusedOrderItemApiClient())

            service.submitOrder(sessionId = null, items = emptyList(), tableId = 3L, expectedNextEmployeeId = null)

            assertNull(api.requests.single().expectedNextEmployeeId)
        }
}

private class CapturingOrderApiClient : OrderApiClient {
    val requests = mutableListOf<OrderDto.CreateOrderRequestDto>()

    override suspend fun createOrder(request: OrderDto.CreateOrderRequestDto): OrderDto.OrderResponseDto {
        requests += request
        return OrderDto.OrderResponseDto(
            orderId = 1L,
            orderNumber = "O1",
            employeeId = "x",
            tableNumber = "3",
            orderTime = "2026-10-16T10:00:00",
            orderTotal = "0.00",
        )
    }

    override suspend fun getOrdersByTableSessionId(tableSessionId: Long) = throw NotImplementedError()

    override suspend fun printReferencePrint(request: OrderItemDto.ReferencePrintRequestDto) = throw NotImplementedError()
}

private class UnusedOrderItemApiClient : OrderItemApiClient {
    override suspend fun getOrderItemsForTable(
        sessionId: Long,
        itemStatus: ItemStatus?,
    ) = throw NotImplementedError()

    override suspend fun cancelOrderItems(
        request: OrderItemDto.CancelOrderItemsRequestDto,
        sourceTableId: Long,
    ) = throw NotImplementedError()

    override suspend fun transferOrderItems(
        request: OrderItemDto.TransferOrderItemsRequestDto,
        sourceTableId: Long,
    ) = throw NotImplementedError()

    override suspend fun getCancellations(
        startDate: String,
        endDate: String,
    ) = throw NotImplementedError()

    override suspend fun getTransferHistory(tableSessionId: Long) = throw NotImplementedError()

    override suspend fun getTransfers(
        startDate: String,
        endDate: String,
    ) = throw NotImplementedError()
}
