/**
 * Data provider interface for product category read operations.
 *
 * Stage C Batch 9: flipped from `Result<T, Error>` to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider

import org.example.project.core.data.models.ProductCategory

/**
 * Read-only data provider for product categories.
 */
interface ProductCategoryDataProvider {
    /** Gets all product categories. */
    suspend fun getAllCategories(includeInactive: Boolean = false): List<ProductCategory>

    /** Gets a specific category by ID, or null if not found. */
    suspend fun getCategoryById(id: Long): ProductCategory?
}
