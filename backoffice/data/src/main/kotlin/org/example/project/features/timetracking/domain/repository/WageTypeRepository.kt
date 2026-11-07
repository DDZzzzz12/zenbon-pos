package org.example.project.features.timetracking.domain.repository

import org.example.project.features.timetracking.domain.models.WageType
import java.util.UUID

interface WageTypeRepository {
    fun findAll(): List<WageType>

    fun findById(id: UUID): WageType?

    fun findByCode(code: Int): WageType?

    fun findDefault(): WageType?

    fun create(
        code: Int,
        name: String,
        description: String?,
        isDefault: Boolean,
    ): WageType

    fun update(
        id: UUID,
        code: Int?,
        name: String?,
        description: String?,
        isDefault: Boolean?,
    ): WageType?

    fun delete(id: UUID): Boolean
}
