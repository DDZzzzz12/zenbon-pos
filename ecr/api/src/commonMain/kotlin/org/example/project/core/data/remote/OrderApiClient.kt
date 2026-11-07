/**
 * API client interface for order operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto

interface OrderApiClient {
    suspend fun getOrdersByTableSessionId(tableSessionId: Long): List<OrderDto.OrderResponseDto>

    /** @return the booked order, or null when the server's 2xx carried no decodable body. */
    suspend fun createOrder(request: OrderDto.CreateOrderRequestDto): OrderDto.OrderResponseDto?

    suspend fun printReferencePrint(request: OrderItemDto.ReferencePrintRequestDto)
}
