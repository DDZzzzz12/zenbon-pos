package org.example.project.core.data.database

import org.example.project.database.PosDatabase
import org.example.project.money.Decimal

/**
 * Data manager for product operations with local database.
 */
class ProductDataManager(
    private val database: PosDatabase,
) {
    fun insertOrUpdateProduct(
        id: Long,
        productNumber: String,
        name: String,
        enName: String?,
        chName: String?,
        description: String?,
        price: Decimal,
        productType: String,
        categoryId: Long,
        isActive: Long,
        allowCustomPrice: Long = 0L,
        imageUrl: String? = null,
    ) {
        database.localProductsQueries.insertOrUpdateProduct(
            id,
            productNumber,
            name,
            enName,
            chName,
            description,
            price,
            productType,
            categoryId,
            isActive,
            allowCustomPrice,
            imageUrl,
        )
    }

    fun getAllProducts() = database.localProductsQueries.getAllProducts().executeAsList()

    fun getAllProductsWithInactive() = database.localProductsQueries.getAllProductsWithInactive().executeAsList()

    fun getProductById(id: Long) = database.localProductsQueries.getProductById(id).executeAsOneOrNull()

    fun getProductsByCategory(categoryId: Long) = database.localProductsQueries.getProductsByCategory(categoryId).executeAsList()

    /**
     * Search products by name or product number.
     *
     * Returns up to 5 products matching the search query, prioritizing:
     * 1. Exact product number match
     * 2. Product number starts with query
     * 3. Product name starts with query
     * 4. Product contains query anywhere
     *
     * @param query Search text (product name or product number)
     * @return List of matching products, limited to 5 results
     */
    fun searchProducts(query: String) =
        database.localProductsQueries
            .searchProducts(
                query,
                query,
                query,
                query,
                query,
            ).executeAsList()

    fun deleteProduct(id: Long) {
        database.localProductsQueries.deleteProduct(id)
    }

    fun clearAllProducts() {
        database.localProductsQueries.clearAllProducts()
    }
}
