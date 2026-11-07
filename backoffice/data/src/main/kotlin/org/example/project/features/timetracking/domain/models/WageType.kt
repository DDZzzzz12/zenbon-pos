package org.example.project.features.timetracking.domain.models

import org.example.project.features.timetracking.database.tables.WageTypeTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

class WageType(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<WageType>(WageTypeTable)

    var code by WageTypeTable.code
    var name by WageTypeTable.name
    var description by WageTypeTable.description
    var isDefault by WageTypeTable.isDefault
}
