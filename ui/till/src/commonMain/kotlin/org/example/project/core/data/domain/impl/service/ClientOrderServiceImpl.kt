/**
 * Implementation of ClientOrderService for order operations.
 *
 * Handles order submission, item retrieval, and item management operations
 * through the order and order-item API clients.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.domain.impl.service

import org.example.project.core.data.domain.service.ClientOrderService
import org.example.project.core.data.models.Items
import org.example.project.core.data.models.toCreateItemDto
import org.example.project.core.data.remote.ApprovalOutcome
import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.data.remote.OrderItemApiClient
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.order.ItemStatus

class ClientOrderServiceImpl(
    private val orderApiClient: OrderApiClient,
    private val orderItemApiClient: OrderItemApiClient,
) : ClientOrderService {
    /**
     * Submits an order in ONE request. With [tableId] the server resolves — or, for a free table,
     * opens — the table's session under its row lock, in the same transaction that books the items,
     * so an interrupted submit can never leave an empty open session behind. [sessionId] only
     * matters without [tableId] (voucher flows); otherwise it is a placeholder the server ignores.
     */
    override suspend fun submitOrder(
        sessionId: Long?,
        items: List<Items>,
        tableId: Long?,
        forEmployeeNumber: String?,
        expectedNextEmployeeId: String?,
    ): OrderDto.OrderResponseDto? =
        orderApiClient.createOrder(
            OrderDto.CreateOrderRequestDto(
                tableInfoId = sessionId ?: 0L,
                tableId = tableId,
                forEmployeeNumber = forEmployeeNumber,
                // What the till's label showed when the table was opened; only meaningful when the server
                // picks the owner.
                expectedNextEmployeeId = if (forEmployeeNumber == null) expectedNextEmployeeId else null,
                orderItems = items.map { it.toCreateItemDto() },
            ),
        )

    override suspend fun getOrderItemsForTable(tableId: Long): List<OrderItemDto.OrderItemResponseDto> =
        orderItemApiClient.getOrderItemsForTable(tableId, ItemStatus.OPEN)

    override suspend fun cancelOrderItems(
        itemIds: List<Long>,
        reason: String?,
        sourceTableId: Long,
    ): ApprovalOutcome {
        val request =
            OrderItemDto.CancelOrderItemsRequestDto(
                itemIds = itemIds,
                reason = reason,
            )
        return orderItemApiClient.cancelOrderItems(request, sourceTableId)
    }

    override suspend fun transferOrderItems(
        itemIds: List<Long>,
        targetTableId: Long,
        sourceTableId: Long,
        reason: String,
    ): ApprovalOutcome {
        val request =
            OrderItemDto.TransferOrderItemsRequestDto(
                itemIds = itemIds,
                targetTableId = targetTableId,
                reason = reason,
            )
        return orderItemApiClient.transferOrderItems(request, sourceTableId)
    }
}
