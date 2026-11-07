package org.example.project.core.data.database

import org.example.project.database.PosDatabase
import org.example.project.money.Decimal

/**
 * Data manager for choice group option operations with local database.
 */
class ChoiceGroupOptionDataManager(
    private val database: PosDatabase,
) {
    fun insertOrReplace(
        groupId: Long,
        optionId: Long,
        priceDelta: Decimal,
        sortPosition: Long,
        isDefault: Long,
    ) {
        database.localChoiceGroupOptionsQueries.insertOrReplace(
            groupId,
            optionId,
            priceDelta,
            sortPosition,
            isDefault,
        )
    }

    fun getByGroup(groupId: Long) = database.localChoiceGroupOptionsQueries.getByGroup(groupId).executeAsList()

    fun deleteByGroup(groupId: Long) {
        database.localChoiceGroupOptionsQueries.deleteByGroup(groupId)
    }

    fun clearAll() {
        database.localChoiceGroupOptionsQueries.clearAll()
    }
}
