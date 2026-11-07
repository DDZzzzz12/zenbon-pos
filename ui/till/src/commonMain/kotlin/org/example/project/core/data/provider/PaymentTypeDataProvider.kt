/**
 * Data provider interface for payment type read operations.
 *
 * Stage C Batch 9: flipped from `Result<T, Error>` to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider

import org.example.project.core.data.models.PaymentType

/**
 * Read-only data provider for payment types.
 */
interface PaymentTypeDataProvider {
    /** Gets all payment types. */
    suspend fun getAllPaymentTypes(): List<PaymentType>

    /** Gets active payment types only. */
    suspend fun getActivePaymentTypes(): List<PaymentType>

    /** Gets a specific payment type by ID, or null if not found. */
    suspend fun getPaymentTypeById(id: Long): PaymentType?
}
