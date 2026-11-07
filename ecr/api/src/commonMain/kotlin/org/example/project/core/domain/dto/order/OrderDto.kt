package org.example.project.core.domain.dto.order

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.rotation.RotationDto

/**
 * Data Transfer Objects for Order API operations.
 *
 * Container object for all Order-related DTOs including request validation,
 * response formatting, and data transformation utilities. Follows the standard
 * object container pattern for organized DTO management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container object for all Order-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 */
object OrderDto {
    /**
     * Response DTO for order data.
     *
     * Used for API responses when returning order information to clients.
     * Contains all public fields from the entity in a serializable format.
     */
    @Serializable
    data class OrderResponseDto(
        /** Order unique identifier */
        val orderId: Long,
        /** Business order number */
        val orderNumber: String,
        /** Employee who created the order */
        val employeeId: String,
        /** Table where order was placed */
        val tableNumber: String,
        /** Order creation timestamp */
        val orderTime: String, // ISO string format for JSON compatibility
        /** Total order amount */
        val orderTotal: String, // Converted from BigDecimal for JSON compatibility
        /** TSE transaction  if associated */
        val tseTransaction: String? = null,
        /** List of order items in this order */
        val orderItems: List<OrderItemDto.OrderItemResponseDto> = emptyList(),
        /**
         * True when the rotation moved on between what the till showed ([CreateOrderRequestDto.expectedNextEmployeeId])
         * and this order opening the table, so the table went to [rotationOwner] instead.
         */
        val rotationShifted: Boolean = false,
        /** The employee who actually owns the table; set only when [rotationShifted]. */
        val rotationOwner: RotationDto.EmployeeRefDto? = null,
    ) {
        companion object
    }

    /**
     * Request DTO for creating new orders.
     *
     * Used for API requests when creating new orders.
     * Contains required fields for order setup.
     */
    @Serializable
    data class CreateOrderRequestDto(
        /**
         * Legacy attach target: the id of a specific table session. Kept for
         * backward compatibility during the transition to server-owned session
         * resolution. When [tableId] is provided the server ignores this field
         * and resolves the table's current active session under a row lock.
         *
         * A caller-supplied session id can be stale (the session may have been
         * billed/completed meanwhile), which is the root cause of orders
         * attaching to a completed session — hence the migration to [tableId].
         */
        val tableInfoId: Long,
        /**
         * Preferred attach target: the id of the table. When non-null the server
         * resolves the table's current active session (creating a fresh one if
         * none is active) under a table-row lock, and this becomes the source of
         * truth for session resolution — stale [tableInfoId] values are ignored.
         */
        val tableId: Long? = null,
        /**
         * Who owns the session when this order OPENS the table (the table was free). Same meaning as
         * `forEmployeeNumber` on the legacy `GET /api/tables/{id}/currentSession`: a manager ordering
         * for a named waiter. Ignored when the table already has an open session, and ignored
         * without [tableId]. An order station must not send it (409 STATION_TABLE_REQUIRES_ROTATION).
         */
        val forEmployeeNumber: String? = null,
        /**
         * The employee id the till showed as "next" when the order was composed. Ignored unless this
         * order opens a free table that rotates; if the rotation has since moved on, the response
         * reports `rotationShifted`.
         */
        val expectedNextEmployeeId: String? = null,
        /** List of order items to include in the order */
        val orderItems: List<OrderItemDto.CreateOrderItemRequestDto> = emptyList(),
    ) {
        /**
         * Validates the create request data.
         *
         * Ensures all required fields are present and values
         * meet business requirements before processing.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            // When tableId is supplied the server resolves the session from the
            // table, so a stale/placeholder tableInfoId is acceptable. Otherwise
            // tableInfoId must identify a real session.
            if (tableId != null) {
                require(tableId > 0) { "Table ID must be positive" }
            } else {
                require(tableInfoId > 0) { "Table Info ID must be positive" }
            }

            require(orderItems.isNotEmpty()) { "Order Items must not be empty" }

            // Validate order items if provided
            orderItems.forEach { item ->
                item.validate()
            }
        }
    }

    /**
     * Summary DTO for order list responses.
     *
     * Contains essential order information for list views
     * without full detail to optimize response size.
     */
    @Serializable
    data class OrderSummaryDto(
        /** Order unique identifier */
        val orderId: Long,
        /** Business order number */
        val orderNumber: String,
        /** Order creation timestamp */
        val orderTime: String,
        /** Total order amount */
        val orderTotal: String,
        /** Table number or name for display */
        val tableNumber: String? = null,
    ) {
        companion object
    }
}
