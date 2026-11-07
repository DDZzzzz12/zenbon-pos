package org.example.project.testutils.fakes

import org.example.project.core.data.models.ProductCategory
import org.example.project.core.data.provider.ProductCategoryDataProvider

class FakeProductCategoryDataProvider : ProductCategoryDataProvider {
    var getAllCategoriesResult: List<ProductCategory> = emptyList()
    var getCategoryByIdResult: ProductCategory? = null

    var getAllCategoriesThrows: Throwable? = null
    var getCategoryByIdThrows: Throwable? = null

    var getAllCategoriesCallCount = 0
    var getCategoryByIdCallCount = 0

    override suspend fun getAllCategories(includeInactive: Boolean): List<ProductCategory> {
        getAllCategoriesCallCount++
        getAllCategoriesThrows?.let { throw it }
        return getAllCategoriesResult
    }

    override suspend fun getCategoryById(id: Long): ProductCategory? {
        getCategoryByIdCallCount++
        getCategoryByIdThrows?.let { throw it }
        return getCategoryByIdResult
    }
}
