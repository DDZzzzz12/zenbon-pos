package org.example.project.features.timetracking.data.repository

import org.example.project.features.timetracking.database.tables.WageTypeTable
import org.example.project.features.timetracking.domain.models.WageType
import org.example.project.features.timetracking.domain.repository.WageTypeRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

class WageTypeRepositoryImpl : WageTypeRepository {
    override fun findAll(): List<WageType> = WageType.all().toList()

    override fun findById(id: UUID): WageType? = WageType.findById(id)

    override fun findByCode(code: Int): WageType? = WageType.find { WageTypeTable.code eq code }.firstOrNull()

    override fun findDefault(): WageType? = WageType.find { WageTypeTable.isDefault eq true }.firstOrNull()

    override fun create(
        code: Int,
        name: String,
        description: String?,
        isDefault: Boolean,
    ): WageType =
        WageType.new {
            this.code = code
            this.name = name
            this.description = description
            this.isDefault = isDefault
        }

    override fun update(
        id: UUID,
        code: Int?,
        name: String?,
        description: String?,
        isDefault: Boolean?,
    ): WageType? {
        val wageType = WageType.findById(id) ?: return null
        code?.let { wageType.code = it }
        name?.let { wageType.name = it }
        description?.let { wageType.description = it }
        isDefault?.let { wageType.isDefault = it }
        return wageType
    }

    override fun delete(id: UUID): Boolean {
        val wageType = WageType.findById(id) ?: return false
        wageType.delete()
        return true
    }
}
