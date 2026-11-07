package org.example.project.core.data.database

import org.example.project.database.PosDatabase

/**
 * Data manager for product-extras assignment junction table operations.
 */
class ProductExtrasAssignmentDataManager(
    private val database: PosDatabase,
) {
    fun getAll() = database.localProductExtrasAssignmentQueries.getAll().executeAsList()

    fun getByProductId(productId: Long) = database.localProductExtrasAssignmentQueries.getByProductId(productId).executeAsList()

    fun insertOrReplace(
        productId: Long,
        extrasId: Long,
        updatedAt: String = "",
    ) {
        database.localProductExtrasAssignmentQueries.insertOrReplace(productId, extrasId, updatedAt)
    }

    fun deleteByProductAndExtra(
        productId: Long,
        extrasId: Long,
    ) {
        database.localProductExtrasAssignmentQueries.deleteByProductAndExtra(productId, extrasId)
    }

    fun deleteAll() {
        database.localProductExtrasAssignmentQueries.deleteAll()
    }
}
