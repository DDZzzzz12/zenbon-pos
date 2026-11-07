package org.example.project.features.reservation.mapper

import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.features.reservation.domain.models.Reservation

fun Reservation.toResponse(): ReservationDto.ResponseDto =
    ReservationDto.ResponseDto(
        id = this.id.value,
        customerId =
            this.customer
                ?.id
                ?.value
                ?.toString(),
        customerName = this.customer?.let { "${it.firstName} ${it.lastName}".trim() },
        guestName = this.guestName,
        guestPhone = this.guestPhone,
        partySize = this.partySize,
        reservationDate = this.reservationDate.toString(),
        reservationTime = this.reservationTime.toString(),
        durationMinutes = this.durationMinutes,
        status = this.status,
        notes = this.notes,
        createdBy =
            this.createdByEmployee.id.value
                .toString(),
        createdByName = this.createdByEmployee.fullName,
        updatedBy =
            this.updatedByEmployee
                ?.id
                ?.value
                ?.toString(),
        createdAt = this.createdAt.toString(),
        updatedAt = this.updatedAt.toString(),
        tableGroups = this.tableGroups,
        maxTablesPerGroup = this.maxTablesPerGroup,
        locationPreference = this.locationPreference,
        tableAssignments = this.tableAssignments.map { it.toAssignmentDto() },
    )
