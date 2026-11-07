package org.example.project.core.domain.dto.order

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.utils.requireValidReason
import org.example.project.money.Decimal

/**
 * Data Transfer Objects for OrderItem API operations.
 *
 * Container object for all OrderItem-related DTOs including request validation,
 * response formatting, and data transformation utilities. Follows the standard
 * object container pattern for organized DTO management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container object for all OrderItem-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 */
object OrderItemDto {
    /**
     * Response DTO for order item data.
     *
     * Used for API responses when returning order item information to clients.
     * Contains all public fields from the entity in a serializable format.
     */
    @Serializable
    data class OrderItemResponseDto(
        /** Order item unique identifier */
        val orderItemIds: List<Long>,
        /** Reference to parent order */
        val ordersId: Set<Long>,
        /** Reference to ordered product */
        val product: ProductDto.OrderItemProductDto,
        /** Unit price at time of order */
        val unitPrice: String,
        val finalPrice: String,
        /**
         * Per-unit CHARGED gross incl. priced options and the line discount — the
         * Anhang I p. 117 „Brutto-Einzelpreis der Bestellzeile" that the Bestellung-V1
         * TSE processData signs (audit TSER-6). Carried un-rounded (full precision) so the
         * signing side truncates it to 2dp; NOT the UI-facing bare base [unitPrice].
         * Defaulted for backward-compatible deserialization; production paths always set it.
         */
        val chargedUnitPrice: String = "0.00",
        /** Current status of the order item */
        val itemStatus: String,
        /** Reference to applicable VAT rate */
        val vatClass: String,
        /** VAT rate fraction as an exact string (e.g., "0.19" for 19 %) */
        val vatRate: String,
        /** Whether custom pricing was applied */
        val customPrice: Boolean,
        val discount: Double,
        /** Whether this is a takeaway order */
        val takeAway: Boolean,
        /** Whether this item was transferred from another order */
        val transferred: Boolean,
        /** Additional notes or customization requests */
        val sideNotes: String? = null,
        /** Quantity of items with identical properties */
        val quantity: Int,
        val extras: List<SelectedOptionDto>? = null,
    ) {
        companion object
    }

    /** A single selected option sent when creating an order item. */
    @Serializable
    data class SelectedOptionRequestDto(
        val optionId: Int,
        val groupId: Int? = null,
        val quantity: Int = 1,
    )

    /** A captured option returned on an order item (snapshot). */
    @Serializable
    data class SelectedOptionDto(
        val optionId: Int? = null,
        val name: String,
        val priceDelta: String, // BigDecimal serialized as String
        val groupName: String? = null,
        /**
         * The group the option was picked from, so a captured line can be re-submitted:
         * [SelectedOptionRequestDto.groupId] is required by the server's `resolveSelections`,
         * which re-prices from the `(groupId, optionId)` placement. [groupName] remains the
         * display label. Null for legacy flat extras and for rows captured before it was stored.
         */
        val groupId: Int? = null,
        val quantity: Int = 1,
    )

    /**
     * Request DTO for creating new order items.
     *
     * Used for API requests when adding items to orders.
     * Contains required fields for order item creation.
     */
    @Serializable
    data class CreateOrderItemRequestDto(
        /** Product ID to add to order */
        val productId: Long,
        /** Custom unit price — exact decimal string (uses product default if not provided) */
        val unitPrice: String,
        /** Quantity of items to add */
        val quantity: Int = 1,
        /** Discount multiplier (1.00 = no discount, 0.85 = 15% off) — a ratio, not a money amount */
        val discount: Double = 1.00,
        /** Whether custom pricing is being applied */
        val customPrice: Boolean = false,
        /** Whether this is a takeaway order */
        val takeAway: Boolean = false,
        /** Additional notes or customization requests */
        val sideNotes: String? = null,
        /** Legacy flat option ids. Used only when `selections` is null; sending both is refused (400). */
        val extras: List<Int>? = null,
        /** Grouped option selections (preferred). Falls back to `extras` when null. */
        val selections: List<SelectedOptionRequestDto>? = null,
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
            require(productId > 0) { "Product ID must be positive" }
            // `discount` is a MULTIPLIER the server applies directly to the line
            // (`(unitPrice + extrasDelta) * discount`), and it was previously unbounded — a negative
            // value produced a negative line price, and one above 1 silently overcharged. The
            // resulting order is TSE-signed and exported to DSFinV-K, so an out-of-range multiplier
            // corrupts the fiscal record rather than merely the till total.
            //
            // These are BOUNDS, not authorization, and they apply to everyone. Authorization is a
            // separate gate and now a real one: `PriceAuthority` / `DISCOUNT_GRANT` / `PRICE_OVERRIDE`,
            // enforced in `OrderServiceImpl` since Stage 3. Deliberately not repeated there — a range
            // check copied into two places is a range check that drifts.
            require(discount.isFinite()) { "Discount multiplier must be a finite number" }
            require(discount in 0.0..1.0) { "Discount multiplier must be between 0.0 and 1.0" }
            unitPrice?.let { price ->
                val parsed =
                    Decimal.parseOrNull(price)
                        ?: throw IllegalArgumentException("Unit price must be a valid decimal number")
                require(!parsed.isNegative) { "Unit price cannot be negative" }
                require(parsed <= Decimal.parse("999999.99")) { "Unit price cannot exceed 999999.99" }
            }
            sideNotes?.let { notes ->
                require(notes.isNotBlank()) { "Side notes cannot be blank if provided" }
                require(notes.length <= 10000) { "Side notes cannot exceed 10000 characters" }
            }
            selections?.let { sel ->
                require(sel.all { it.optionId > 0 }) { "All option IDs must be positive" }
                require(sel.all { it.quantity in 1..99 }) { "Option quantity must be 1..99" }
            }
        }
    }

    /**
     * Request DTO for cancelling order items.
     *
     * Used for API requests when cancelling one or more order items.
     * Requires authorization and records the cancellation for audit purposes.
     */
    @Serializable
    data class CancelOrderItemsRequestDto(
        /** List of order item IDs to cancel */
        val itemIds: List<Long>,
        /** Required reason for cancellation (for audit trail); nullable on the wire so an old client gets a 400, not a decode error */
        val reason: String? = null,
    ) {
        /**
         * Validates the cancel request data.
         *
         * Ensures the request contains valid item IDs and reason format.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(itemIds.isNotEmpty()) { "At least one order item ID is required for cancellation" }
            require(itemIds.size <= 50) { "Cannot cancel more than 50 items in a single request" }
            require(itemIds.all { it > 0 }) { "All order item IDs must be positive" }
            require(itemIds.toSet().size == itemIds.size) { "Duplicate item IDs found in request" }

            reason.requireValidReason()
        }
    }

    /**
     * Request DTO for transferring order items between tables.
     *
     * Used for API requests when moving order items from one table to another.
     * All items must be from the same source table and target table must exist.
     */
    @Serializable
    data class TransferOrderItemsRequestDto(
        /** List of order item IDs to transfer */
        val itemIds: List<Long>,
        /** Target table ID to transfer items to */
        val targetTableId: Long,
        /** Required reason for the transfer; nullable on the wire so an old client gets a 400, not a decode error */
        val reason: String? = null,
    ) {
        /**
         * Validates the transfer request data.
         *
         * Ensures the request contains valid item IDs and target table ID.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(itemIds.isNotEmpty()) { "At least one order item ID is required for transfer" }
            require(itemIds.size <= 50) { "Cannot transfer more than 50 items in a single request" }
            require(itemIds.all { it > 0 }) { "All order item IDs must be positive" }
            require(itemIds.toSet().size == itemIds.size) { "Duplicate item IDs found in request" }
            require(targetTableId > 0) { "Target table ID must be positive" }
            reason.requireValidReason()
        }
    }

    /**
     * Response DTO for operation results (cancel/transfer).
     *
     * Used for API responses when operations complete successfully.
     * Provides confirmation and summary of affected items.
     */
    @Serializable
    data class OrderItemOperationResultDto(
        /** Number of items successfully processed */
        val affectedItemsCount: Int,
        /** List of processed item IDs for confirmation */
        val processedItemIds: List<Long>,
        /** Operation type performed */
        val operationType: String,
        /** Total financial amount impacted by the operation — exact decimal string */
        val totalAmount: String? = null,
        /** Additional result message */
        val message: String,
    ) {
        companion object {
            /**
             * Creates a result for successful cancellation operation.
             */
            fun forCancellation(
                itemIds: List<Long>,
                totalAmount: String? = null,
            ): OrderItemOperationResultDto =
                OrderItemOperationResultDto(
                    affectedItemsCount = itemIds.size,
                    processedItemIds = itemIds,
                    operationType = "CANCEL",
                    totalAmount = totalAmount,
                    message = "Successfully cancelled ${itemIds.size} order item(s)",
                )

            /**
             * Creates a result for successful transfer operation.
             */
            fun forTransfer(
                itemIds: List<Long>,
                targetTableId: Long,
                totalAmount: String? = null,
            ): OrderItemOperationResultDto =
                OrderItemOperationResultDto(
                    affectedItemsCount = itemIds.size,
                    processedItemIds = itemIds,
                    operationType = "TRANSFER",
                    totalAmount = totalAmount,
                    message = "Successfully transferred ${itemIds.size} order item(s) to table $targetTableId",
                )
        }
    }

    /**
     * Response DTO for cancellation data.
     *
     * Used for API responses when returning cancellation information to clients.
     */
    @Serializable
    data class CancellationResponseDto(
        val cancellationId: Long,
        val sessionId: Long,
        val tableNumber: String,
        val orderItemId: OrderItemResponseDto,
        val canceledAmount: String, // String for BigDecimal
        val reason: String?,
        val employeeNumber: String,
        val createdAt: String, // ISO String
    )

    /**
     * Request DTO for printing a reference receipt.
     *
     * When orderItemIds is empty, all non-cancelled open items for the table are printed.
     * When orderItemIds is provided, only those specific items are printed.
     */
    @Serializable
    data class ReferencePrintRequestDto(
        /** Table ID to print reference for */
        val tableId: Long,
        /** Specific order item IDs to print (empty = all open items) */
        val orderItemIds: List<Long> = emptyList(),
    ) {
        fun validate() {
            require(tableId > 0) { "Table ID must be positive" }
            require(orderItemIds.all { it > 0 }) { "All order item IDs must be positive" }
        }
    }
}
