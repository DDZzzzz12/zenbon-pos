package org.example.project.features.reservation.mapper

import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.features.reservation.domain.models.ReservationTableAssignment

fun ReservationTableAssignment.toAssignmentDto(): ReservationDto.AssignmentDto {
    val t = this.table
    return ReservationDto.AssignmentDto(
        tableId = t.id.value.toString(),
        tableNumber = t.tableNumber,
        seats = t.seats ?: 0,
        groupNumber = this.groupNumber,
        assignedBy =
            this.assignedByEmployee.id.value
                .toString(),
        assignedAt = this.assignedAt.toString(),
    )
}
