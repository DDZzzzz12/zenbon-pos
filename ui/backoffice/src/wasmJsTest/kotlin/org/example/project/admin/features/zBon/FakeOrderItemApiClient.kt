package org.example.project.admin.features.zBon

import org.example.project.core.data.remote.ApprovalOutcome
import org.example.project.core.data.remote.OrderItemApiClient
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.order.ItemStatus

/** [OrderItemApiClient] fake: no cancellations, no transfers; writes are not expected. */
class FakeOrderItemApiClient : OrderItemApiClient {
    override suspend fun getOrderItemsForTable(
        sessionId: Long,
        itemStatus: ItemStatus?,
    ): List<OrderItemDto.OrderItemResponseDto> = emptyList()

    override suspend fun cancelOrderItems(
        request: OrderItemDto.CancelOrderItemsRequestDto,
        sourceTableId: Long,
    ): ApprovalOutcome = throw NotImplementedError()

    override suspend fun transferOrderItems(
        request: OrderItemDto.TransferOrderItemsRequestDto,
        sourceTableId: Long,
    ): ApprovalOutcome = throw NotImplementedError()

    override suspend fun getCancellations(
        startDate: String,
        endDate: String,
    ): List<OrderItemDto.CancellationResponseDto> = emptyList()

    override suspend fun getTransferHistory(tableSessionId: Long): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto> = emptyList()

    override suspend fun getTransfers(
        startDate: String,
        endDate: String,
    ): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto> = emptyList()
}
