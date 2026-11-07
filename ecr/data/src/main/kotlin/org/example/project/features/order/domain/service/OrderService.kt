/**
 * Service interface for order business logic operations.
 *
 * Defines the contract for order business operations including order management,
 * validation, and coordination with repository layer.
 *
 * Errors are surfaced by throwing [org.example.project.infrastructure.errors.PosException];
 * the global StatusPages handler converts them to RFC 7807 problem+json responses.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.order.domain.service

import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.enums.auth.Capability
import java.util.UUID

interface OrderService {
    /**
     * Retrieves all orders from the system.
     */
    suspend fun getAllOrders(): List<OrderDto.OrderResponseDto>

    /**
     * Retrieves an order by its unique ID, or null if not found.
     */
    suspend fun getOrderById(orderId: Long): OrderDto.OrderResponseDto?

    /**
     * Retrieves an order by its business order number, or null if not found.
     */
    suspend fun getOrderByOrderNumber(orderNumber: String): OrderDto.OrderResponseDto?

    /**
     * Retrieves all orders for a specific table session.
     */
    suspend fun getOrdersByTableSessionId(tableSessionId: Long): List<OrderDto.OrderResponseDto>

    /**
     * Retrieves all orders created by a specific employee.
     */
    suspend fun getOrdersByEmployeeId(employeeId: UUID): List<OrderDto.OrderResponseDto>

    /**
     * Retrieves order summaries for list views.
     */
    suspend fun getOrderSummaries(): List<OrderDto.OrderSummaryDto>

    /**
     * Creates a new order with business rule validation, TSE signing, and printing.
     *
     * @param actorCapabilities What the acting principal holds **for this request**, exactly as the
     *   route-authorization plugin resolved it (`call.heldCapabilities()`): the permission group,
     *   narrowed by the station mask, plus whatever an active manager step-up lends. The steep-discount
     *   and custom-price checks read this set. They used to re-resolve the employee's GROUP on their
     *   own, a second opinion that could not see a step-up — so a manager's PIN unlocked the till's
     *   discount panel and `/me`, and the server then refused the same 25 % with 403 (R5-1). There is
     *   deliberately no default: a caller must say what the actor holds, not have it guessed.
     *
     * A line carrying any discount or a custom price is refused with 403 when the order is registered
     * under the order station's shared account ([employeeId] is the `TERMINAL_DEVICE` employee — a
     * station JWT or a bare station device token), unless `PriceAuthority.mayPriceLinesAs` allows it:
     * pricing is not done under the station account at all (finding E-2), and no capability or
     * step-up changes that. A person's own login prices as that person on any device (owner's
     * decision 2026-09-28: attribution, not device).
     *
     * @param allowVoucherProduct A line for a `ProductType.VOUCHER` product is refused with 400
     *   `VOUCHER_PRODUCT_NOT_ORDERABLE` unless this is true. Only the voucher service's table-session
     *   sale passes true: it creates the voucher record that gives the line its value. Ordered
     *   directly, the `VOU` product books a 0,00 sale with no voucher behind it (QA MD-10).
     */
    suspend fun createOrder(
        request: OrderDto.CreateOrderRequestDto,
        employeeId: String,
        deviceId: String,
        actorCapabilities: Set<Capability>,
        allowVoucherProduct: Boolean = false,
    ): OrderDto.OrderResponseDto

    /**
     * Prints a reference receipt (Referenzdruck) for a table's current order items.
     */
    suspend fun referencePrint(
        tableId: Long,
        orderItemIds: List<Long>,
        employeeId: String,
    )
}
