package org.example.project.core.data.database

import org.example.project.database.PosDatabase

/**
 * Data manager for product↔choice-group attachment operations with local database.
 */
class ProductChoiceGroupDataManager(
    private val database: PosDatabase,
) {
    fun insertOrReplace(
        productId: Long,
        groupId: Long,
        sortPosition: Long,
    ) {
        database.localProductChoiceGroupsQueries.insertOrReplace(productId, groupId, sortPosition)
    }

    fun getByProduct(productId: Long) = database.localProductChoiceGroupsQueries.getByProduct(productId).executeAsList()

    fun getProductIdsWithGroups(): Set<Long> =
        database.localProductChoiceGroupsQueries
            .getAllProductIds()
            .executeAsList()
            .toSet()

    fun deleteByProduct(productId: Long) {
        database.localProductChoiceGroupsQueries.deleteByProduct(productId)
    }

    fun deleteByGroup(groupId: Long) {
        database.localProductChoiceGroupsQueries.deleteByGroup(groupId)
    }

    fun clearAll() {
        database.localProductChoiceGroupsQueries.clearAll()
    }
}
