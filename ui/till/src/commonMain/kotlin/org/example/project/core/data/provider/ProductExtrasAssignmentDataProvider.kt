/**
 * Data provider interface for product-extras assignment relationships.
 *
 * Stage C Batch 9: flipped from `Result<T, Error>` to throws-style.
 */
package org.example.project.core.data.provider

import org.example.project.core.data.models.ProductExtrasAssignment

/**
 * Read-only data provider for product-extras assignment relationships.
 */
interface ProductExtrasAssignmentDataProvider {
    /** Gets the extra IDs associated with a product. */
    suspend fun getExtrasForProduct(productId: Long): List<Int>

    /** Gets all product-extras assignments. */
    suspend fun getAllAssignments(): List<ProductExtrasAssignment>
}
