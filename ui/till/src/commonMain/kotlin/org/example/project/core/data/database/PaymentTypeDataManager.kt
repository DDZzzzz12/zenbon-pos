/**
 * Data manager for payment type operations with local database.
 *
 * Provides access to local SQLDelight database for payment type storage,
 * supporting offline functionality and data synchronization.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.database

import org.example.project.database.PosDatabase

/**
 * Data manager for payment type operations with local database.
 *
 * Handles CRUD operations for payment types in the local SQLite database
 * using SQLDelight generated queries.
 */
class PaymentTypeDataManager(
    private val database: PosDatabase,
) {
    /**
     * Insert or update a payment type in local storage.
     *
     * @param id Payment type ID
     * @param name Payment type name
     * @param isActive Whether the payment type is active
     */
    fun insertOrUpdatePaymentType(
        id: Long,
        name: String,
        en: String?,
        ch: String?,
        isActive: Long,
        createsReceivable: Long,
    ) {
        database.localPaymentTypesQueries.insertOrUpdatePaymentType(
            id,
            name,
            en,
            ch,
            isActive,
            createsReceivable,
        )
    }

    /**
     * Get all payment types from local storage.
     *
     * @return List of all payment types
     */
    fun getAllPaymentTypes() = database.localPaymentTypesQueries.getAllPaymentTypes().executeAsList()

    /**
     * Get all active payment types from local storage.
     *
     * @return List of active payment types
     */
    fun getActivePaymentTypes() = database.localPaymentTypesQueries.getActivePaymentTypes().executeAsList()

    /**
     * Get a specific payment type by ID.
     *
     * @param id Payment type ID
     * @return Payment type or null if not found
     */
    fun getPaymentTypeById(id: Long) = database.localPaymentTypesQueries.getPaymentTypeById(id).executeAsOneOrNull()

    /**
     * Get a specific payment type by name.
     *
     * @param name Payment type name
     * @return Payment type or null if not found
     */
    fun getPaymentTypeByName(name: String) = database.localPaymentTypesQueries.getPaymentTypeByName(name).executeAsOneOrNull()

    /**
     * Count total number of payment types.
     *
     * @return Total count of payment types
     */
    fun countPaymentTypes() = database.localPaymentTypesQueries.countPaymentTypes().executeAsOne()

    /**
     * Count active payment types.
     *
     * @return Count of active payment types
     */
    fun countActivePaymentTypes() = database.localPaymentTypesQueries.countActivePaymentTypes().executeAsOne()

    /**
     * Delete a specific payment type from local storage.
     *
     * @param id Payment type ID to delete
     */
    fun deletePaymentType(id: Long) {
        database.localPaymentTypesQueries.deletePaymentType(id)
    }

    /**
     * Clear all payment types from local storage.
     *
     * Used when performing a full sync to ensure clean state.
     */
    fun clearAllPaymentTypes() {
        database.localPaymentTypesQueries.clearAllPaymentTypes()
    }
}
