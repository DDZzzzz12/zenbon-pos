/**
 * Repository interface for ProductExtras data operations.
 *
 * Defines the contract for ProductExtras data access operations.
 * Returns plain entities; exceptions propagate naturally to the service layer.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.domain.repository

import org.example.project.features.product.domain.models.ProductExtras
import java.math.BigDecimal

/**
 * Repository interface for ProductExtras operations.
 *
 * Provides data access methods for product extras management including
 * CRUD operations, validation, and extras-specific business logic.
 */
interface ProductExtrasRepository {
    /**
     * Retrieve all product extras.
     */
    fun getAllExtras(includeInactive: Boolean = false): List<ProductExtras>

    /**
     * Retrieve a product extra by its ID.
     */
    fun getExtraById(id: Int): ProductExtras?

    /**
     * Retrieve a product extra by its name.
     */
    fun getExtraByName(name: String): ProductExtras?

    /**
     * Create a new product extra.
     */
    fun createExtra(
        name: String,
        enName: String? = null,
        chName: String? = null,
        price: BigDecimal,
        isActive: Boolean = true,
    ): ProductExtras

    /**
     * Update an existing product extra.
     */
    fun updateExtra(
        id: Int,
        name: String,
        price: BigDecimal,
        isActive: Boolean,
        enName: String?,
        chName: String?,
    ): ProductExtras?

    /**
     * Delete a product extra (soft-delete).
     * @return The name of the deleted extra
     */
    fun deleteExtra(id: Int): String

    /**
     * Get all active product extras.
     */
    fun getActiveExtras(): List<ProductExtras>

    /**
     * Get extras by category (through product category relationship).
     */
    fun getExtrasByCategory(categoryId: Int): List<ProductExtras>

    /**
     * Get product extras updated after the specified timestamp for synchronization.
     */
    fun getExtrasUpdatedAfter(lastSyncTime: kotlinx.datetime.LocalDateTime): List<ProductExtras>
}
