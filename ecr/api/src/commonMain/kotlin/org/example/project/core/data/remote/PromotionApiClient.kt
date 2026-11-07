package org.example.project.core.data.remote

import org.example.project.core.domain.dto.promotion.PromotionDto

/**
 * Stage C Batch 7: throws-style.
 */
interface PromotionApiClient {
    suspend fun getActivePromotions(): List<PromotionDto.PromotionResponseDto>

    suspend fun getAllPromotions(): List<PromotionDto.PromotionResponseDto>

    suspend fun getPromotionById(id: Long): PromotionDto.PromotionResponseDto

    suspend fun createPromotion(request: PromotionDto.CreatePromotionRequestDto): PromotionDto.PromotionResponseDto

    suspend fun updatePromotion(
        id: Long,
        request: PromotionDto.UpdatePromotionRequestDto,
    ): PromotionDto.PromotionResponseDto

    suspend fun deactivatePromotion(id: Long)

    suspend fun getPromotionUsage(id: Long): PromotionDto.PromotionUsageDto
}
