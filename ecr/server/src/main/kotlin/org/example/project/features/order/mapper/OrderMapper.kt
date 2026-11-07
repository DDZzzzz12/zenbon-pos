/**
 * Order entity to DTO mapping functions.
 *
 * Provides conversion methods for Order entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.order.mapper

import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.utils.builder.tse.toTseQrCodeContent
import org.example.project.utils.formatter.formatDecimal

/**
 * Converts Orders entity to OrderResponseDto.
 */
fun Orders.toOrderResponseDto(
    items: List<OrderItem>? = null,
    withTseData: Boolean = false,
): OrderDto.OrderResponseDto =
    OrderDto.OrderResponseDto(
        orderId = this.id.value,
        orderNumber = this.orderNumber,
        employeeId = this.employee.employeeNumber,
        tableNumber = this.tableSession.table.tableNumber,
        orderTime = this.orderTime.toString(),
        orderTotal = formatDecimal(this.orderTotal),
        tseTransaction = if (withTseData)this.tseTransaction?.toTseQrCodeContent() else null,
        orderItems =
            items?.toGroupedOrderItemResponseDto() ?: this.items
                .toList()
                .toGroupedOrderItemResponseDto(),
    )

/**
 * Converts Orders entity to OrderSummaryDto.
 */
fun Orders.toOrderSummaryDto(): OrderDto.OrderSummaryDto =
    OrderDto.OrderSummaryDto(
        orderId = this.id.value,
        orderNumber = this.orderNumber,
        orderTime = this.orderTime.toString(),
        orderTotal = formatDecimal(this.orderTotal),
        tableNumber = this.tableSession.table.tableNumber,
    )
