package org.example.project.core.domain.dto.order

import kotlinx.serialization.Serializable

object ItemTransferHistoryDto {
    @Serializable
    data class ItemTransferHistoryResponseDto(
        val transferId: Long,
        val direction: String,
        val fromTableNumber: String,
        val toTableNumber: String,
        val employeeName: String,
        val transferTime: String,
        val items: List<OrderItemDto.OrderItemResponseDto>,
        val totalAmount: String,
    )
}
