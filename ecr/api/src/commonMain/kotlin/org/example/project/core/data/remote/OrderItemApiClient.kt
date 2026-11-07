/**
 * API client interface for order item operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.order.ItemStatus

interface OrderItemApiClient {
    suspend fun getOrderItemsForTable(
        sessionId: Long,
        itemStatus: ItemStatus?,
    ): List<OrderItemDto.OrderItemResponseDto>

    /** @return whether the items were cancelled, or an approval request was raised instead. */
    suspend fun cancelOrderItems(
        request: OrderItemDto.CancelOrderItemsRequestDto,
        sourceTableId: Long,
    ): ApprovalOutcome

    /** @return whether the items were transferred, or an approval request was raised instead. */
    suspend fun transferOrderItems(
        request: OrderItemDto.TransferOrderItemsRequestDto,
        sourceTableId: Long,
    ): ApprovalOutcome

    suspend fun getCancellations(
        startDate: String,
        endDate: String,
    ): List<OrderItemDto.CancellationResponseDto>

    suspend fun getTransferHistory(tableSessionId: Long): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto>

    suspend fun getTransfers(
        startDate: String,
        endDate: String,
    ): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto>
}
