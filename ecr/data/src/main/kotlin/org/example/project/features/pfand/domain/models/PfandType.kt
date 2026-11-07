/**
 * Exposed DAO entity for a Pfand (deposit) type.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.pfand.domain.models

import org.example.project.features.pfand.database.tables.PfandTypeTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class PfandType(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<PfandType>(PfandTypeTable)

    var name by PfandTypeTable.name
    var depositValue by PfandTypeTable.depositValue
    var vatClass by PfandTypeTable.vatClass
    var isActive by PfandTypeTable.isActive
    var createdAt by PfandTypeTable.createdAt
    var updatedAt by PfandTypeTable.updatedAt
}
