package org.example.project.features.payment.domain.repository

import org.example.project.features.payment.domain.models.PaymentType

/**
 * Repository interface for PaymentType data access.
 *
 * Defines the contract for all PaymentType database operations including
 * CRUD operations, querying, and business-specific data access patterns.
 * Returns plain entities or nullable types; exceptions propagate naturally
 * to the service layer.
 */
interface PaymentTypeRepository {
    /**
     * Retrieves all payment types from the database.
     *
     * @param includeInactive Whether to include inactive payment types
     * @return List of PaymentType entities
     */
    fun getAllPaymentTypes(includeInactive: Boolean = false): List<PaymentType>

    /**
     * Retrieves a payment type by its unique identifier.
     *
     * @param id The unique identifier of the payment type
     * @return PaymentType entity or null if not found
     */
    fun getPaymentTypeById(id: Long): PaymentType?

    /**
     * Retrieves a payment type by its name.
     *
     * @param name The name of the payment type
     * @return PaymentType entity or null if not found
     */
    fun getPaymentTypeByName(name: String): PaymentType?

    /**
     * Creates a new payment type in the database.
     *
     * @param name The name of the payment type
     * @param en Optional English name for the payment type
     * @param ch Optional Chinese name for the payment type
     * @param isCash Whether this is a cash-related payment type
     * @param isActive Whether the payment type is initially active
     * @return Created PaymentType entity
     */
    fun createPaymentType(
        name: String,
        en: String? = null,
        ch: String? = null,
        isCash: Boolean = false,
        isActive: Boolean,
        createsReceivable: Boolean = false,
    ): PaymentType

    /**
     * Updates an existing payment type in the database.
     *
     * @param id The unique identifier of the payment type to update
     * @param name Optional new name for the payment type
     * @param en Optional new English name for the payment type
     * @param ch Optional new Chinese name for the payment type
     * @param isCash Optional new cash-related flag for the payment type
     * @param isActive Optional new active status for the payment type
     * @return Updated PaymentType entity or null if not found
     */
    fun updatePaymentType(
        id: Long,
        name: String? = null,
        en: String? = null,
        ch: String? = null,
        isCash: Boolean? = null,
        isActive: Boolean? = null,
        createsReceivable: Boolean? = null,
    ): PaymentType?

    /**
     * Deletes a payment type from the database (soft delete - deactivates).
     *
     * @param id The unique identifier of the payment type to delete
     * @throws NoSuchElementException if the payment type is not found
     */
    fun deletePaymentType(id: Long)

    /**
     * Checks if a payment type name already exists.
     *
     * @param name The name to check for uniqueness
     * @param excludeId Optional ID to exclude from the check (for updates)
     * @return true if the name exists, false otherwise
     */
    fun existsByName(
        name: String,
        excludeId: Long? = null,
    ): Boolean

    /**
     * Retrieves active payment types only.
     *
     * @return List of active PaymentType entities
     */
    fun getActivePaymentTypes(): List<PaymentType>

    /**
     * Counts total number of payment types.
     *
     * @param activeOnly Whether to count only active payment types
     * @return Count as Long
     */
    fun countPaymentTypes(activeOnly: Boolean = false): Long

    /**
     * Checks if a payment type is currently in use by any payments.
     *
     * @param id The unique identifier of the payment type
     * @return true if the payment type is in use
     */
    fun isPaymentTypeInUse(id: Long): Boolean

    /**
     * Retrieves payment types updated after a specific timestamp.
     *
     * @param lastSyncTime ISO-8601 timestamp string
     * @return List of PaymentType entities
     */
    fun getPaymentTypesUpdatedAfter(lastSyncTime: String): List<PaymentType>
}
