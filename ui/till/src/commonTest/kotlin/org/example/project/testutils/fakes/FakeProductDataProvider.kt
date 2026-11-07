package org.example.project.testutils.fakes

import org.example.project.core.data.models.Product
import org.example.project.core.data.provider.ProductDataProvider

class FakeProductDataProvider : ProductDataProvider {
    var getAllProductsResult: List<Product> = emptyList()
    var getAllProductsWithInactiveResult: List<Product> = emptyList()
    var getProductByIdResult: Product? = null
    var getProductsByCategoryResult: List<Product> = emptyList()
    var searchProductsResult: List<Product> = emptyList()
    var getFavouriteProductIdsResult: List<Long> = emptyList()

    var getAllProductsThrows: Throwable? = null
    var getAllProductsWithInactiveThrows: Throwable? = null
    var getProductByIdThrows: Throwable? = null
    var getProductsByCategoryThrows: Throwable? = null
    var searchProductsThrows: Throwable? = null
    var getFavouriteProductIdsThrows: Throwable? = null

    var getAllProductsCallCount = 0
    var getAllProductsWithInactiveCallCount = 0
    var getProductByIdCallCount = 0
    var getProductsByCategoryCallCount = 0
    var searchProductsCallCount = 0

    override suspend fun getAllProducts(): List<Product> {
        getAllProductsCallCount++
        getAllProductsThrows?.let { throw it }
        return getAllProductsResult
    }

    override suspend fun getAllProductsWithInactive(): List<Product> {
        getAllProductsWithInactiveCallCount++
        getAllProductsWithInactiveThrows?.let { throw it }
        return getAllProductsWithInactiveResult
    }

    override suspend fun getProductById(id: Long): Product? {
        getProductByIdCallCount++
        getProductByIdThrows?.let { throw it }
        return getProductByIdResult
    }

    override suspend fun getProductsByCategory(categoryId: Long): List<Product> {
        getProductsByCategoryCallCount++
        getProductsByCategoryThrows?.let { throw it }
        return getProductsByCategoryResult
    }

    override suspend fun searchProducts(query: String): List<Product> {
        searchProductsCallCount++
        searchProductsThrows?.let { throw it }
        return searchProductsResult
    }

    override suspend fun getFavouriteProductIds(): List<Long> {
        getFavouriteProductIdsThrows?.let { throw it }
        return getFavouriteProductIdsResult
    }
}
