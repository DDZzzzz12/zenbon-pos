package org.example.project.features.setup.domain.repository

import org.example.project.features.payment.domain.models.Vat
import java.math.BigDecimal

/**
 * Repository interface for VAT data access operations.
 *
 * Defines the contract for VAT database operations including CRUD operations,
 * VAT search, and classification management. Returns plain entities or nullable
 * types; exceptions propagate naturally to the service layer.
 */
interface VatRepository {
    /**
     * Retrieves all VAT rates from the database.
     *
     * @return List of all Vat entities, ordered by VAT class
     */
    fun findAll(): List<Vat>

    /**
     * Finds a VAT rate by its unique ID.
     *
     * @param vatId Unique identifier of the VAT rate
     * @return Vat entity or null if not found
     */
    fun findById(vatId: String): Vat?

    /**
     * Finds a VAT rate by its classification.
     *
     * @param vatClass VAT classification identifier
     * @return Vat entity or null if not found
     */
    fun findByVatClass(vatClass: String): Vat?

    /**
     * Finds VAT rates within a specific rate range.
     *
     * @param minRate Minimum VAT rate (inclusive)
     * @param maxRate Maximum VAT rate (inclusive)
     * @return List of Vat entities within the range
     */
    fun findByRateRange(
        minRate: BigDecimal,
        maxRate: BigDecimal,
    ): List<Vat>

    /**
     * Creates or retrieves standard German VAT rates.
     *
     * Returns common German tax rates (0%, 7%, 19%).
     *
     * @return List of standard German Vat entities
     */
    fun setStandardGermanRates(): List<Vat>

    /**
     * Creates a new VAT rate record.
     *
     * @param vatId VAT identifier
     * @param vatClass VAT classification identifier
     * @param vatRate VAT rate as decimal
     * @return Created Vat entity
     */
    fun create(
        vatId: String,
        vatClass: String,
        vatRate: BigDecimal,
    ): Vat

    /**
     * Updates an existing VAT rate record.
     *
     * @param vatId ID of the VAT rate to update
     * @param vatClass Updated VAT classification (nullable for partial updates)
     * @param vatRate Updated VAT rate (nullable for partial updates)
     * @return Updated Vat entity or null if not found
     */
    fun update(
        vatId: String,
        vatClass: String? = null,
        vatRate: BigDecimal? = null,
    ): Vat?

    /**
     * Deletes a VAT rate by ID.
     *
     * @param vatId ID of the VAT rate to delete
     * @return The vatClass name of the deleted VAT rate
     * @throws IllegalArgumentException if VAT rate not found or referenced by other entities
     */
    fun delete(vatId: String): String

    /**
     * Checks if a VAT rate exists for the given ID.
     *
     * @param vatId ID of the VAT rate to check
     * @return true if the VAT rate exists, false otherwise
     */
    suspend fun exists(vatId: String): Boolean

    /**
     * Checks if a VAT class is already in use.
     *
     * @param vatClass VAT classification to check
     * @return true if the VAT class exists, false otherwise
     */
    suspend fun vatClassExists(vatClass: String): Boolean

    /**
     * Checks if a VAT rate can be safely deleted.
     *
     * @param vatId ID of the VAT rate to check
     * @return true if the VAT rate can be deleted, false if referenced
     */
    suspend fun canDelete(vatId: String): Boolean

    /**
     * Counts the total number of VAT rates.
     *
     * @return Total count of VAT rates in the database
     */
    suspend fun count(): Long

    /**
     * Counts VAT rates within a specific rate range.
     *
     * @param minRate Minimum VAT rate (inclusive)
     * @param maxRate Maximum VAT rate (inclusive)
     * @return Count of VAT rates within the range
     */
    suspend fun countByRateRange(
        minRate: BigDecimal,
        maxRate: BigDecimal,
    ): Long

    /**
     * Creates multiple VAT rates in a single transaction.
     *
     * @param vatRates List of (vatClass, vatRate) pairs to create
     * @return List of created Vat entities
     */
    fun createBatch(vatRates: List<Pair<String, BigDecimal>>): List<Vat>
}
