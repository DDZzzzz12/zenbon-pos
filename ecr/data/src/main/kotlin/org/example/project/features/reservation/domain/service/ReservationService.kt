package org.example.project.features.reservation.domain.service

import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.enums.reservation.ReservationStatus

interface ReservationService {
    suspend fun getById(id: Long): ReservationDto.ResponseDto

    suspend fun getByDate(
        date: String,
        status: ReservationStatus? = null,
        limit: Int? = null,
        offset: Int = 0,
    ): List<ReservationDto.ResponseDto>

    suspend fun create(
        employeeId: String,
        request: ReservationDto.CreateRequestDto,
    ): ReservationDto.ResponseDto

    suspend fun update(
        id: Long,
        employeeId: String,
        request: ReservationDto.UpdateRequestDto,
    ): ReservationDto.ResponseDto

    suspend fun updateStatus(
        id: Long,
        employeeId: String,
        request: ReservationDto.UpdateStatusRequestDto,
    ): ReservationDto.ResponseDto

    suspend fun delete(id: Long)

    suspend fun checkAvailability(
        date: String,
        time: String? = null,
        partySize: Int? = null,
    ): ReservationDto.AvailabilityResponseDto

    suspend fun search(
        query: String,
        limit: Int? = null,
        offset: Int = 0,
    ): List<ReservationDto.ResponseDto>
}
