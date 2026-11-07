package org.example.project.core.data.database

import org.example.project.database.PosDatabase

/**
 * Data manager for choice group operations with local database.
 */
class ChoiceGroupDataManager(
    private val database: PosDatabase,
) {
    fun insertOrUpdate(
        id: Long,
        name: String,
        enName: String?,
        chName: String?,
        minSelections: Long,
        maxSelections: Long,
        isActive: Long,
    ) {
        database.localChoiceGroupsQueries.insertOrUpdate(
            id,
            name,
            enName,
            chName,
            minSelections,
            maxSelections,
            isActive,
        )
    }

    fun getAll() = database.localChoiceGroupsQueries.getAll().executeAsList()

    fun getById(id: Long) = database.localChoiceGroupsQueries.getById(id).executeAsOneOrNull()

    fun deleteById(id: Long) {
        database.localChoiceGroupsQueries.deleteById(id)
    }

    fun clearAll() {
        database.localChoiceGroupsQueries.clearAll()
    }
}
