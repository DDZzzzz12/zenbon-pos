package org.example.project.features.timetracking.domain.repository

import org.example.project.features.timetracking.domain.models.CostCenter
import java.util.UUID

interface CostCenterRepository {
    fun findAll(includeInactive: Boolean = false): List<CostCenter>

    fun findById(id: UUID): CostCenter?

    fun findByCode(code: String): CostCenter?

    fun create(
        code: String,
        name: String,
        isActive: Boolean,
    ): CostCenter

    fun update(
        id: UUID,
        code: String?,
        name: String?,
        isActive: Boolean?,
    ): CostCenter?

    fun delete(id: UUID): Boolean
}
