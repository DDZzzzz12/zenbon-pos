package org.example.project.features.customexport.domain.models

import org.example.project.features.customexport.database.tables.ExportDefinitionFieldTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for one chosen column of a saved export. See [ExportDefinitionFieldTable]. */
class ExportDefinitionField(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ExportDefinitionField>(ExportDefinitionFieldTable)

    var definition by ExportDefinition referencedOn ExportDefinitionFieldTable.definitionId
    var fieldKey by ExportDefinitionFieldTable.fieldKey
    var position by ExportDefinitionFieldTable.position
    var heading by ExportDefinitionFieldTable.heading

    override fun toString(): String = "ExportDefinitionField(id=$id, fieldKey=$fieldKey, position=$position)"
}
