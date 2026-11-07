package org.example.project.features.table.domain.repository

import org.example.project.features.table.domain.models.LocationType

interface LocationTypeRepository {
    fun getAll(): List<LocationType>

    fun getById(id: Long): LocationType?

    fun create(
        name: String,
        englishName: String,
        chineseName: String,
        isVirtual: Boolean,
        isTakeaway: Boolean,
        sortOrder: Int,
    ): LocationType

    fun update(
        id: Long,
        name: String?,
        englishName: String?,
        chineseName: String?,
        isVirtual: Boolean?,
        isTakeaway: Boolean?,
        sortOrder: Int?,
    ): LocationType?

    fun delete(id: Long): Boolean

    fun countTablesUsing(id: Long): Long

    fun countRoomElementsUsing(id: Long): Long
}
