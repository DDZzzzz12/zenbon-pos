/**
 * Payment type status enumeration for bill payment tracking.
 *
 * Defines the possible states of a payment transaction to track
 * whether payments have been completed successfully or refunded.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.bill

/**
 * Enumeration representing the status of a payment transaction.
 *
 * Used to track the lifecycle of payments from completion to potential refunds.
 * This helps maintain accurate financial records and transaction history.
 */
enum class PaymentStatus {
    /** Payment has been successfully completed and processed */
    COMPLETED,

    /** Payment has been refunded to the customer */
    REFUNDED,
}
