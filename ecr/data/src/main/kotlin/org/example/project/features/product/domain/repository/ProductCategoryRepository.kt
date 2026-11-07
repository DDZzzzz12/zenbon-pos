/**
 * Repository interface for ProductCategory data operations.
 *
 * Defines the contract for ProductCategory data access operations.
 * Returns plain entities; exceptions propagate naturally to the service layer.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.domain.repository

import org.example.project.features.product.domain.models.ProductCategory
import java.math.BigDecimal

/**
 * Repository interface for ProductCategory operations.
 *
 * Provides data access methods for product category management including
 * CRUD operations, validation, and category-specific business logic.
 */
interface ProductCategoryRepository {
    /**
     * Retrieve all product categories.
     */
    fun getAllCategories(includeInactive: Boolean = false): List<ProductCategory>

    /**
     * Retrieve categories filtered by visibility scopes.
     */
    fun getCategoriesByVisibility(visibilityScopes: List<String>): List<ProductCategory>

    /**
     * Retrieve a product category by its ID.
     */
    fun getCategoryById(id: Int): ProductCategory?

    /**
     * Retrieve a product category by its name.
     */
    fun getCategoryByName(name: String): ProductCategory?

    /**
     * Create a new product category.
     */
    fun createCategory(
        id: Int? = null,
        name: String,
        enName: String? = null,
        chName: String? = null,
        description: String? = null,
        isActive: Boolean = true,
        sortOrder: BigDecimal? = null,
        visibility: String = "EVERYWHERE",
    ): ProductCategory

    /**
     * Update an existing product category.
     */
    fun updateCategory(
        id: Int,
        name: String,
        description: String?,
        isActive: Boolean,
        enName: String?,
        chName: String?,
        sortOrder: BigDecimal? = null,
        visibility: String? = null,
    ): ProductCategory?

    /**
     * Delete a product category (soft-delete).
     * @throws NoSuchElementException if not found
     */
    fun deleteCategory(id: Int)

    /**
     * Number of ACTIVE products filed under the category. A category that still holds active
     * products must not be deleted: the soft delete hides the category on the till while its
     * products stay active, so they would vanish from every grid without being deactivated.
     */
    fun countActiveProducts(categoryId: Int): Long

    /**
     * Get all active product categories.
     */
    fun getActiveCategories(): List<ProductCategory>

    /**
     * Get product categories updated after the specified timestamp for synchronization.
     */
    fun getCategoriesUpdatedAfter(lastSyncTime: kotlinx.datetime.LocalDateTime): List<ProductCategory>

    /**
     * Update category sort position.
     */
    fun updateCategorySortPosition(
        categoryId: Long,
        newSortPosition: Double,
    ): ProductCategory?
}
