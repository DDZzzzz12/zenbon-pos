package org.example.project.features.reservation.domain.service

import org.example.project.core.domain.dto.reservation.ReservationRuleDto

interface ReservationRuleService {
    suspend fun getById(id: Long): ReservationRuleDto.ResponseDto

    suspend fun getByDateRange(
        startDate: String,
        endDate: String,
    ): List<ReservationRuleDto.ResponseDto>

    suspend fun create(request: ReservationRuleDto.CreateRequestDto): ReservationRuleDto.ResponseDto

    suspend fun update(
        id: Long,
        request: ReservationRuleDto.UpdateRequestDto,
    ): ReservationRuleDto.ResponseDto

    suspend fun delete(id: Long)
}
