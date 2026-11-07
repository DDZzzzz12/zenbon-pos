package org.example.project.features.timetracking.data.repository

import org.example.project.features.timetracking.database.tables.CostCenterTable
import org.example.project.features.timetracking.domain.models.CostCenter
import org.example.project.features.timetracking.domain.repository.CostCenterRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

class CostCenterRepositoryImpl : CostCenterRepository {
    override fun findAll(includeInactive: Boolean): List<CostCenter> =
        if (includeInactive) {
            CostCenter.all().toList()
        } else {
            CostCenter.find { CostCenterTable.isActive eq true }.toList()
        }

    override fun findById(id: UUID): CostCenter? = CostCenter.findById(id)

    override fun findByCode(code: String): CostCenter? = CostCenter.find { CostCenterTable.code eq code }.firstOrNull()

    override fun create(
        code: String,
        name: String,
        isActive: Boolean,
    ): CostCenter =
        CostCenter.new {
            this.code = code
            this.name = name
            this.isActive = isActive
        }

    override fun update(
        id: UUID,
        code: String?,
        name: String?,
        isActive: Boolean?,
    ): CostCenter? {
        val costCenter = CostCenter.findById(id) ?: return null
        code?.let { costCenter.code = it }
        name?.let { costCenter.name = it }
        isActive?.let { costCenter.isActive = it }
        return costCenter
    }

    override fun delete(id: UUID): Boolean {
        val costCenter = CostCenter.findById(id) ?: return false
        costCenter.delete()
        return true
    }
}
