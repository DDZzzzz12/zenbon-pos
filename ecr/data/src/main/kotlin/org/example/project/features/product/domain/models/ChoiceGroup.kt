package org.example.project.features.product.domain.models

import org.example.project.features.product.database.tables.ChoiceGroupTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

class ChoiceGroup(
    id: EntityID<Int>,
) : IntEntity(id) {
    companion object : IntEntityClass<ChoiceGroup>(ChoiceGroupTable)

    var name by ChoiceGroupTable.name
    var enName by ChoiceGroupTable.enName
    var chName by ChoiceGroupTable.chName
    var minSelections by ChoiceGroupTable.minSelections
    var maxSelections by ChoiceGroupTable.maxSelections
    var isActive by ChoiceGroupTable.isActive
    var updatedAt by ChoiceGroupTable.updatedAt
}
