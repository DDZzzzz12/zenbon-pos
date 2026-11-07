/**
 * API client-backed implementation of [ProductCategoryDataProvider].
 *
 * Stage C Batch 9: flipped to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider.remote

import org.example.project.core.data.models.ProductCategory
import org.example.project.core.data.provider.ProductCategoryDataProvider
import org.example.project.core.data.remote.ProductCategoryApiClient

class ApiClientProductCategoryDataProvider(
    private val productCategoryApiClient: ProductCategoryApiClient,
) : ProductCategoryDataProvider {
    override suspend fun getAllCategories(includeInactive: Boolean): List<ProductCategory> =
        productCategoryApiClient
            .getAllProductCategories(
                isActive = if (includeInactive) null else true,
            ).map { ProductCategory.fromDto(it) }

    override suspend fun getCategoryById(id: Long): ProductCategory? = ProductCategory.fromDto(productCategoryApiClient.getProductCategoryById(id))
}
