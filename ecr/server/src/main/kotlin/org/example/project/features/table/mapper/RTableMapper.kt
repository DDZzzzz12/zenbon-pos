/**
 * Restaurant Table entity to DTO mapping functions.
 *
 * Provides conversion methods for RTable entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.table.mapper

import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.features.table.domain.models.RTable

/**
 * Converts RTable entity to RTableResponseDto.
 */
fun RTable.toResponse(): RTableDto.RTableResponseDto {
    val session = this.currentSession
    val lt = this.locationTypeRef
    return RTableDto.RTableResponseDto(
        id = this.id.value,
        tableNumber = this.tableNumber,
        seats = this.seats,
        locationTypeId = lt.id.value,
        locationTypeName = lt.name,
        locationTypeEnglishName = lt.englishName,
        locationTypeChineseName = lt.chineseName,
        isVirtual = lt.isVirtual,
        isTakeaway = lt.isTakeaway,
        tableStatus = this.tableStatus.name,
        currentSessionId = session?.id?.value,
        hasActiveSession = session != null,
        positionX = this.positionX,
        positionY = this.positionY,
        rotation = this.rotation,
        width = this.width,
        height = this.height,
        currentEmployeeNumber = session?.employeeId?.employeeNumber,
        isTraining = session?.isTraining ?: false,
        isActive = this.isActive,
        assignmentPriority = this.assignmentPriority,
        isSystem = this.isSystem,
    )
}
