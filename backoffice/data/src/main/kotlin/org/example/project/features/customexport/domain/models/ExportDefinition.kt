package org.example.project.features.customexport.domain.models

import org.example.project.features.customexport.database.tables.ExportDefinitionFieldTable
import org.example.project.features.customexport.database.tables.ExportDefinitionTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for a saved custom export. See [ExportDefinitionTable]. */
class ExportDefinition(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ExportDefinition>(ExportDefinitionTable)

    var shop by ShopInfo referencedOn ExportDefinitionTable.shopId
    var name by ExportDefinitionTable.name
    var subjectKey by ExportDefinitionTable.subjectKey
    var createdByEmployeeId by ExportDefinitionTable.createdByEmployeeId
    var dateFrom by ExportDefinitionTable.dateFrom
    var dateTo by ExportDefinitionTable.dateTo
    var enabledToggles by ExportDefinitionTable.enabledToggles
    var createdAt by ExportDefinitionTable.createdAt
    var updatedAt by ExportDefinitionTable.updatedAt

    /**
     * The chosen columns, in **no guaranteed order**.
     *
     * Exposed's referrers carry no ordering, so every read that renders or runs the export must sort
     * on `ExportDefinitionField.position` itself. Relying on the order rows come back in works right
     * up until the first edit.
     */
    val fields by ExportDefinitionField referrersOn ExportDefinitionFieldTable.definitionId

    override fun toString(): String = "ExportDefinition(id=$id, name=$name, subjectKey=$subjectKey)"
}
