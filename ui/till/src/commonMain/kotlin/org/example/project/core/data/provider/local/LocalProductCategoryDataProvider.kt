/**
 * SQLite-backed implementation of [ProductCategoryDataProvider].
 *
 * Stage C Batch 9: flipped to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider.local

import org.example.project.core.data.database.ProductCategoryDataManager
import org.example.project.core.data.models.ProductCategory
import org.example.project.core.data.provider.ProductCategoryDataProvider

class LocalProductCategoryDataProvider(
    private val productCategoryDataManager: ProductCategoryDataManager,
) : ProductCategoryDataProvider {
    override suspend fun getAllCategories(includeInactive: Boolean): List<ProductCategory> =
        productCategoryDataManager
            .getAllProductCategories(includeInactive)
            .map { ProductCategory.fromDatabase(it) }

    override suspend fun getCategoryById(id: Long): ProductCategory? =
        productCategoryDataManager.getProductCategoryById(id)?.let { ProductCategory.fromDatabase(it) }
}
