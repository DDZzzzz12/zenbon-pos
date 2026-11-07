package org.example.project.features.table.data.repository

import org.example.project.features.table.database.tables.LocationTypeTable
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.database.tables.RoomElementTable
import org.example.project.features.table.domain.models.LocationType
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.RoomElement
import org.example.project.features.table.domain.repository.LocationTypeRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder

class LocationTypeRepositoryImpl : LocationTypeRepository {
    override fun getAll(): List<LocationType> = LocationType.all().orderBy(LocationTypeTable.sortOrder to SortOrder.ASC).toList()

    override fun getById(id: Long): LocationType? = LocationType.findById(id)

    override fun create(
        name: String,
        englishName: String,
        chineseName: String,
        isVirtual: Boolean,
        isTakeaway: Boolean,
        sortOrder: Int,
    ): LocationType =
        LocationType.new {
            this.name = name
            this.englishName = englishName
            this.chineseName = chineseName
            this.isVirtual = isVirtual
            this.isTakeaway = isTakeaway
            this.sortOrder = sortOrder
            this.createdAt = currentTimeStamp()
            this.updatedAt = currentTimeStamp()
        }

    override fun update(
        id: Long,
        name: String?,
        englishName: String?,
        chineseName: String?,
        isVirtual: Boolean?,
        isTakeaway: Boolean?,
        sortOrder: Int?,
    ): LocationType? {
        val locationType = LocationType.findById(id) ?: return null
        name?.let { locationType.name = it }
        englishName?.let { locationType.englishName = it }
        chineseName?.let { locationType.chineseName = it }
        isVirtual?.let { locationType.isVirtual = it }
        isTakeaway?.let { locationType.isTakeaway = it }
        sortOrder?.let { locationType.sortOrder = it }
        locationType.updatedAt = currentTimeStamp()
        return locationType
    }

    override fun delete(id: Long): Boolean {
        val locationType = LocationType.findById(id) ?: return false
        locationType.delete()
        return true
    }

    override fun countTablesUsing(id: Long): Long = RTable.find { RTableTable.locationTypeId eq id }.count()

    override fun countRoomElementsUsing(id: Long): Long = RoomElement.find { RoomElementTable.locationTypeId eq id }.count()
}
