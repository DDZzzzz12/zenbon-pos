/**
 * Order status enumeration for order lifecycle management.
 *
 * Defines the various states an order can be in during its lifecycle
 * from initial creation to final completion and payment.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.order

/**
 * Order status enumeration for tracking order lifecycle.
 *
 * Represents different stages of order processing from creation
 * to completion, enabling proper order state management.
 */
enum class OrderStatus(
    val displayName: String,
    val description: String,
) {
    /** Order created but not yet confirmed */
    DRAFT("Draft", "Order created but not yet confirmed"),

    /** Order confirmed and ready for preparation */
    CONFIRMED("Confirmed", "Order confirmed and ready for preparation"),

    /** Order is being prepared in the kitchen/bar */
    IN_PREPARATION("In Preparation", "Order is being prepared in the kitchen/bar"),

    /** Order is ready for serving/pickup */
    READY("Ready", "Order is ready for serving or pickup"),

    /** Order has been served/delivered */
    SERVED("Served", "Order has been served to the customer"),

    /** Order has been completed and paid for */
    COMPLETED("Completed", "Order is completed and paid for"),

    /** Order was cancelled */
    CANCELLED("Cancelled", "Order was cancelled before completion"),

    /** Order was refunded */
    REFUNDED("Refunded", "Order was refunded after completion"),
    ;

    /**
     * Checks if this status allows order modification.
     */
    fun allowsModification(): Boolean =
        when (this) {
            DRAFT, CONFIRMED -> true
            else -> false
        }

    /**
     * Checks if this status represents a final state.
     */
    fun isFinal(): Boolean =
        when (this) {
            COMPLETED, CANCELLED, REFUNDED -> true
            else -> false
        }

    /**
     * Gets valid next statuses from current status.
     */
    fun getValidNextStatuses(): List<OrderStatus> =
        when (this) {
            DRAFT -> listOf(CONFIRMED, CANCELLED)
            CONFIRMED -> listOf(IN_PREPARATION, CANCELLED)
            IN_PREPARATION -> listOf(READY, CANCELLED)
            READY -> listOf(SERVED, CANCELLED)
            SERVED -> listOf(COMPLETED)
            COMPLETED -> listOf(REFUNDED)
            CANCELLED -> emptyList() // No transitions from cancelled
            REFUNDED -> emptyList() // No transitions from refunded
        }
}
