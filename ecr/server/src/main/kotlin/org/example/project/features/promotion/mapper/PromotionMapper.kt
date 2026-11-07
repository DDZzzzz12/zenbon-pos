package org.example.project.features.promotion.mapper

import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.utils.formatter.formatDecimal

fun Promotion.toResponseDto(): PromotionDto.PromotionResponseDto =
    PromotionDto.PromotionResponseDto(
        id = this.id.value,
        name = this.name,
        description = this.description,
        discountType = this.discountType.name,
        discountValue = formatDecimal(this.discountValue),
        scope = this.scope.name,
        targetProductId = this.targetProduct?.id?.value,
        targetProductName = this.targetProduct?.name,
        isMultiUse = this.isMultiUse,
        combinable = this.combinable,
        validFrom = this.validFrom.toString(),
        validUntil = this.validUntil?.toString(),
        isActive = this.isActive,
        createdBy = this.createdBy.employeeNumber,
        createdAt = this.createdAt.toString(),
    )

fun BillPromotion.toResponseDto(): PromotionDto.BillPromotionResponseDto =
    PromotionDto.BillPromotionResponseDto(
        id = this.id.value,
        promotionId = this.promotion.id.value,
        promotionName = this.promotion.name,
        orderItemId = this.orderItem?.id?.value,
        deductedAmount = formatDecimal(this.deductedAmount),
        appliedBy = this.appliedBy.employeeNumber,
        appliedAt = this.appliedAt.toString(),
    )
