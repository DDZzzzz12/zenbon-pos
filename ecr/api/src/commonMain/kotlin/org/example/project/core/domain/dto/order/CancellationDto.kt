package org.example.project.core.domain.dto.order

import kotlinx.serialization.Serializable

object CancellationDto {
    @Serializable
    data class CancellationResponse(
        val sessionId: Long,
        val tableNumber: String,
        val item: OrderDto.OrderResponseDto,
        val createdAt: String,
        val tseInfo: String?,
    )
}
