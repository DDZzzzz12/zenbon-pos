/**
 * Data provider interface for product read operations.
 *
 * Stage C Batch 9: flipped from `Result<T, Error>` to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider

import org.example.project.core.data.models.Product

/**
 * Read-only data provider for products.
 */
interface ProductDataProvider {
    /** Gets all active products. */
    suspend fun getAllProducts(): List<Product>

    /** Gets all products including inactive ones. */
    suspend fun getAllProductsWithInactive(): List<Product>

    /** Gets a specific product by ID, or null if not found. */
    suspend fun getProductById(id: Long): Product?

    /** Gets products filtered by category. */
    suspend fun getProductsByCategory(categoryId: Long): List<Product>

    /** Searches products by name or product number (max 5). */
    suspend fun searchProducts(query: String): List<Product>

    /** Gets favourite product IDs ordered by ranking. */
    suspend fun getFavouriteProductIds(): List<Long>
}
