package org.example.project.features.table.domain.models

import org.example.project.features.table.database.tables.LocationTypeTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class LocationType(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<LocationType>(LocationTypeTable)

    var name by LocationTypeTable.name
    var englishName by LocationTypeTable.englishName
    var chineseName by LocationTypeTable.chineseName
    var isVirtual by LocationTypeTable.isVirtual
    var isTakeaway by LocationTypeTable.isTakeaway
    var sortOrder by LocationTypeTable.sortOrder
    var isSystem by LocationTypeTable.isSystem
    var createdAt by LocationTypeTable.createdAt
    var updatedAt by LocationTypeTable.updatedAt
}
