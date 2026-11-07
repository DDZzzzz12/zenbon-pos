package org.example.project.admin.features.promotion

import org.example.project.core.data.remote.PromotionApiClient
import org.example.project.core.domain.dto.promotion.PromotionDto

/** Hand-written fake; records mutating calls so tests can assert on them. */
class FakePromotionApiClient(
    var promotions: List<PromotionDto.PromotionResponseDto> = emptyList(),
    var usage: PromotionDto.PromotionUsageDto? = null,
    /** Fails [getAllPromotions]; a `var` so a test can arm it *after* the initial load. */
    var onError: (() -> Nothing)? = null,
    private val onDeactivateError: (() -> Nothing)? = null,
) : PromotionApiClient {
    var created: PromotionDto.CreatePromotionRequestDto? = null
    var updatedId: Long? = null
    var updated: PromotionDto.UpdatePromotionRequestDto? = null
    var deactivatedId: Long? = null
    var usageRequestedFor: Long? = null

    override suspend fun getActivePromotions(): List<PromotionDto.PromotionResponseDto> = promotions.filter { it.isActive }

    override suspend fun getAllPromotions(): List<PromotionDto.PromotionResponseDto> {
        onError?.invoke()
        return promotions
    }

    override suspend fun getPromotionById(id: Long): PromotionDto.PromotionResponseDto = promotions.first { it.id == id }

    override suspend fun createPromotion(request: PromotionDto.CreatePromotionRequestDto): PromotionDto.PromotionResponseDto {
        created = request
        return PromotionDto.PromotionResponseDto(
            id = 77L,
            name = request.name,
            description = request.description,
            discountType = request.discountType,
            discountValue = request.discountValue,
            scope = request.scope,
            targetProductId = request.targetProductId,
            targetProductName = null,
            isMultiUse = request.isMultiUse,
            combinable = request.combinable,
            validFrom = request.validFrom,
            validUntil = request.validUntil,
            isActive = true,
            createdBy = "test",
            createdAt = "2026-08-13T00:00:00",
        )
    }

    override suspend fun updatePromotion(
        id: Long,
        request: PromotionDto.UpdatePromotionRequestDto,
    ): PromotionDto.PromotionResponseDto {
        updatedId = id
        updated = request
        return promotions.first { it.id == id }
    }

    override suspend fun deactivatePromotion(id: Long) {
        onDeactivateError?.invoke()
        deactivatedId = id
    }

    override suspend fun getPromotionUsage(id: Long): PromotionDto.PromotionUsageDto {
        usageRequestedFor = id
        return usage ?: PromotionDto.PromotionUsageDto(promotionId = id, timesUsed = 0L, totalDiscountGiven = "0.00")
    }
}
