/**
 * Data provider interface for product extra read operations.
 *
 * Stage C Batch 9: flipped from `Result<T, Error>` to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider

import org.example.project.core.data.models.ProductExtra

/**
 * Read-only data provider for product extras.
 */
interface ProductExtraDataProvider {
    /** Gets all product extras. */
    suspend fun getAllExtras(includeInactive: Boolean = false): List<ProductExtra>

    /** Gets a specific extra by ID, or null if not found. */
    suspend fun getExtraById(id: Long): ProductExtra?
}
