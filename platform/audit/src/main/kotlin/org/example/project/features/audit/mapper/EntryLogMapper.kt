package org.example.project.features.audit.mapper

import org.example.project.core.domain.dto.setup.EntryLogDto
import org.example.project.features.audit.domain.models.EntryLog

fun EntryLog.toResponseDto(): EntryLogDto.EntryLogResponseDto =
    EntryLogDto.EntryLogResponseDto(
        id = this.id.value,
        type = this.type.name,
        operation = this.operation.name,
        entityId = this.entityId,
        entityName = this.entityName,
        message = this.message,
        userId = this.userId?.employeeNumber ?: "UNKOWN",
        createdAt = this.createdAt.toString(),
    )
