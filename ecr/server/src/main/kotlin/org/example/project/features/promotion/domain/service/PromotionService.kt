package org.example.project.features.promotion.domain.service

import org.example.project.core.domain.dto.promotion.PromotionDto

interface PromotionService {
    suspend fun getActivePromotions(): List<PromotionDto.PromotionResponseDto>

    suspend fun getAllPromotions(): List<PromotionDto.PromotionResponseDto>

    suspend fun getPromotionById(id: Long): PromotionDto.PromotionResponseDto

    suspend fun createPromotion(
        employeeId: String,
        request: PromotionDto.CreatePromotionRequestDto,
    ): PromotionDto.PromotionResponseDto

    suspend fun updatePromotion(
        id: Long,
        request: PromotionDto.UpdatePromotionRequestDto,
    ): PromotionDto.PromotionResponseDto

    suspend fun deactivatePromotion(id: Long)

    suspend fun getPromotionUsage(id: Long): PromotionDto.PromotionUsageDto
}
