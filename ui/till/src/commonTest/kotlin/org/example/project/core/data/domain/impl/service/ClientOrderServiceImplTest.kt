package org.example.project.core.data.domain.impl.service

import kotlinx.coroutines.test.runTest
import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.data.remote.OrderItemApiClient
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A table is opened by its first order, in ONE request: the server resolves (or opens) the table's
 * session from `tableId` in the transaction that books the items, so there is nothing to re-resolve
 * and nothing to retry on the client (2026-09-18 e2e finding #3).
 */
class ClientOrderServiceImplTest {
    private fun closedSessionException() =
        PosApiException(
            code = PosErrorCode.SESSION_ALREADY_CLOSED,
            httpStatus = 409,
            message = "session already closed",
            traceId = null,
        )

    @Test
    fun `a first order sends the table and the named waiter in ONE request`() =
        runTest {
            val orderApi = RecordingOrderApiClient()
            val service = ClientOrderServiceImpl(orderApi, NoOpOrderItemApiClient())

            service.submitOrder(sessionId = null, items = emptyList(), tableId = 3L, forEmployeeNumber = "E1", expectedNextEmployeeId = null)

            val sent = orderApi.requests.single()
            assertEquals(3L, sent.tableId)
            assertEquals("E1", sent.forEmployeeNumber)
            assertEquals(0L, sent.tableInfoId) // placeholder, ignored by the server when tableId is set
        }

    @Test
    fun `an order for an open table still carries its session id`() =
        runTest {
            val orderApi = RecordingOrderApiClient()
            val service = ClientOrderServiceImpl(orderApi, NoOpOrderItemApiClient())

            service.submitOrder(sessionId = 7L, items = emptyList(), tableId = 3L, expectedNextEmployeeId = null)

            assertEquals(7L, orderApi.requests.single().tableInfoId)
        }

    @Test
    fun `a failed order is surfaced once, never retried`() =
        runTest {
            val orderApi = RecordingOrderApiClient(failAllWith = closedSessionException())
            val service = ClientOrderServiceImpl(orderApi, NoOpOrderItemApiClient())

            assertFailsWith<PosApiException> {
                service.submitOrder(sessionId = 7L, items = emptyList(), tableId = 3L, expectedNextEmployeeId = null)
            }
            assertEquals(1, orderApi.requests.size)
            Unit
        }
}

/** Records every submitted request; can be configured to fail all calls. */
private class RecordingOrderApiClient(
    private val failAllWith: PosApiException? = null,
) : OrderApiClient {
    val requests = mutableListOf<OrderDto.CreateOrderRequestDto>()

    override suspend fun createOrder(request: OrderDto.CreateOrderRequestDto): OrderDto.OrderResponseDto {
        requests.add(request)
        failAllWith?.let { throw it }
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

private class NoOpOrderItemApiClient : OrderItemApiClient {
    override suspend fun getOrderItemsForTable(
        sessionId: Long,
        itemStatus: org.example.project.core.domain.enums.order.ItemStatus?,
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
