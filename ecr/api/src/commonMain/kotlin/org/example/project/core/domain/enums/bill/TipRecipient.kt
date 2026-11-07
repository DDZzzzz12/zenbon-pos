/**
 * Tip recipient enumeration for bill tip distribution tracking.
 *
 * Defines who receives the tips from customer payments to ensure
 * proper distribution and tracking of gratuity payments.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.bill

/**
 * Enumeration representing who receives the tip from a customer payment.
 *
 * Used to track tip distribution in the point-of-sale system and ensure
 * proper allocation of gratuity payments to the correct recipients.
 */
enum class TipRecipient {
    /** Tips go to the employee who served the customer */
    EMPLOYEE,

    /** Tips go to the business owner */
    OWNER,
}
