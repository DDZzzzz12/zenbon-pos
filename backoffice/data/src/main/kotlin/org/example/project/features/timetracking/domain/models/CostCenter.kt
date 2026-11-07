package org.example.project.features.timetracking.domain.models

import org.example.project.features.timetracking.database.tables.CostCenterTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

class CostCenter(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<CostCenter>(CostCenterTable)

    var code by CostCenterTable.code
    var name by CostCenterTable.name
    var isActive by CostCenterTable.isActive
}
