package org.example.project.core.data.database

import org.example.project.database.PosDatabase

/**
 * Data manager for product category operations with local database.
 */
class ProductCategoryDataManager(
    private val database: PosDatabase,
) {
    fun insertOrUpdateProductCategory(
        id: Long,
        name: String,
        enName: String?,
        chName: String?,
        description: String?,
        isActive: Long,
        sortPosition: Double,
    ) {
        database.localProductCategoriesQueries.insertOrUpdateProductCategory(
            id,
            name,
            enName,
            chName,
            description,
            isActive,
            sortPosition,
        )
    }

    fun getAllProductCategories(includeInactive: Boolean = false) =
        if (includeInactive) {
            database.localProductCategoriesQueries.getAllProductCategoriesIncludingInactive().executeAsList()
        } else {
            database.localProductCategoriesQueries.getAllProductCategories().executeAsList()
        }

    fun getProductCategoryById(id: Long) = database.localProductCategoriesQueries.getProductCategoryById(id).executeAsOneOrNull()

    fun deleteProductCategory(id: Long) {
        database.localProductCategoriesQueries.deleteProductCategory(id)
    }

    fun clearAllProductCategories() {
        database.localProductCategoriesQueries.clearAllProductCategories()
    }

    fun updateSortPositionById(
        sortPosition: Double,
        id: Long,
    ) = database.localProductCategoriesQueries.updateProductCategorySortPosition(sortPosition, id)
}
