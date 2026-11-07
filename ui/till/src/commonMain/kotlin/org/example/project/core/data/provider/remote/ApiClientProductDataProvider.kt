/**
 * API client-backed implementation of [ProductDataProvider].
 *
 * Stage C Batch 9: flipped to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider.remote

import org.example.project.core.data.models.Product
import org.example.project.core.data.provider.ProductDataProvider
import org.example.project.core.data.remote.ProductApiClient

class ApiClientProductDataProvider(
    private val productApiClient: ProductApiClient,
) : ProductDataProvider {
    override suspend fun getAllProducts(): List<Product> = productApiClient.getAllProducts(isActive = true).map { Product.fromDto(it) }

    override suspend fun getAllProductsWithInactive(): List<Product> = productApiClient.getAllProducts().map { Product.fromDto(it) }

    override suspend fun getProductById(id: Long): Product? = Product.fromDto(productApiClient.getProductById(id))

    override suspend fun getProductsByCategory(categoryId: Long): List<Product> = productApiClient.getProductsByCategory(categoryId).map { Product.fromDto(it) }

    override suspend fun searchProducts(query: String): List<Product> = productApiClient.searchProducts(query, limit = 10).map { Product.fromDto(it) }

    override suspend fun getFavouriteProductIds(): List<Long> = productApiClient.getFavouriteProductIds()
}
