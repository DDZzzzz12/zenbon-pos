/**
 * Item status enumeration for order item lifecycle tracking.
 *
 * Defines the possible states of an order item as it progresses through
 * the order fulfillment process from creation to completion.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.order

/**
 * Enumeration representing the status of an individual order item.
 *
 * Used to track the lifecycle of order items from when they are ordered
 * until they are paid for or cancelled, enabling proper order management.
 */
enum class ItemStatus {
    /** Item has been ordered but not yet paid for */
    OPEN,

    /** Item has been paid for and completed */
    PAID,

    /** Item has been cancelled and will not be fulfilled */
    CANCELLED,
}
