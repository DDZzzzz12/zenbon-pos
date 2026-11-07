/**
 * SQLite-backed implementation of [ProductDataProvider].
 *
 * Stage C Batch 9: flipped to throws-style — DB exceptions and remote API
 * exceptions propagate. Favourites are fetched from the server API since they
 * are computed server-side.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider.local

import org.example.project.core.data.database.ProductDataManager
import org.example.project.core.data.models.Product
import org.example.project.core.data.provider.ProductDataProvider
import org.example.project.core.data.remote.ProductApiClient

class LocalProductDataProvider(
    private val productDataManager: ProductDataManager,
    private val productApiClient: ProductApiClient,
) : ProductDataProvider {
    override suspend fun getAllProducts(): List<Product> = productDataManager.getAllProducts().map { Product.fromDatabase(it) }

    override suspend fun getAllProductsWithInactive(): List<Product> = productDataManager.getAllProductsWithInactive().map { Product.fromDatabase(it) }

    override suspend fun getProductById(id: Long): Product? = productDataManager.getProductById(id)?.let { Product.fromDatabase(it) }

    override suspend fun getProductsByCategory(categoryId: Long): List<Product> =
        productDataManager.getProductsByCategory(categoryId).map { Product.fromDatabase(it) }

    override suspend fun searchProducts(query: String): List<Product> = productDataManager.searchProducts(query).map { Product.fromDatabase(it) }

    override suspend fun getFavouriteProductIds(): List<Long> = productApiClient.getFavouriteProductIds()
}
