package org.example.project.testutils.fakes

import org.example.project.core.data.domain.service.ClientOrderService
import org.example.project.core.data.models.Items
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto

/** Fails loudly if a test reaches the order service it declared it never uses. */
class StubClientOrderService : ClientOrderService {
    override suspend fun submitOrder(
        sessionId: Long?,
        items: List<Items>,
        tableId: Long?,
        forEmployeeNumber: String?,
        expectedNextEmployeeId: String?,
    ): OrderDto.OrderResponseDto? = error("unused")

    override suspend fun getOrderItemsForTable(tableId: Long): List<OrderItemDto.OrderItemResponseDto> = error("unused")

    override suspend fun cancelOrderItems(
        itemIds: List<Long>,
        reason: String?,
        sourceTableId: Long,
    ) = error("unused")

    override suspend fun transferOrderItems(
        itemIds: List<Long>,
        targetTableId: Long,
        sourceTableId: Long,
        reason: String,
    ) = error("unused")
}
