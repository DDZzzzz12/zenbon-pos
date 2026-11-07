package org.example.project.core.data.database

import org.example.project.database.PosDatabase
import org.example.project.money.Decimal

/**
 * Data manager for product extra operations with local database.
 */
class ProductExtraDataManager(
    private val database: PosDatabase,
) {
    fun insertOrUpdateProductExtra(
        id: Long,
        name: String,
        enName: String?,
        chName: String?,
        description: String?,
        price: Decimal,
        isActive: Long,
        linkedProductId: Long? = null,
    ) {
        database.localProductExtrasQueries.insertOrUpdateProductExtra(
            id,
            name,
            enName,
            chName,
            description,
            price,
            isActive,
            linkedProductId,
        )
    }

    fun getAllProductExtras() = database.localProductExtrasQueries.getAllProductExtras().executeAsList()

    fun getProductExtraById(id: Long) = database.localProductExtrasQueries.getProductExtraById(id).executeAsOneOrNull()

    fun deleteProductExtra(id: Long) {
        database.localProductExtrasQueries.deleteProductExtra(id)
    }

    fun clearAllProductExtras() {
        database.localProductExtrasQueries.clearAllProductExtras()
    }
}
