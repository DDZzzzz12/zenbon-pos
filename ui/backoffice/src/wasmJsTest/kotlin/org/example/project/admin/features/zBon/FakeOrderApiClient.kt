package org.example.project.admin.features.zBon

import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto

/** [OrderApiClient] fake: a session without orders; writes are not expected. */
class FakeOrderApiClient : OrderApiClient {
    override suspend fun getOrdersByTableSessionId(tableSessionId: Long): List<OrderDto.OrderResponseDto> = emptyList()

    override suspend fun createOrder(request: OrderDto.CreateOrderRequestDto): OrderDto.OrderResponseDto? = throw NotImplementedError()

    override suspend fun printReferencePrint(request: OrderItemDto.ReferencePrintRequestDto) = throw NotImplementedError()
}
