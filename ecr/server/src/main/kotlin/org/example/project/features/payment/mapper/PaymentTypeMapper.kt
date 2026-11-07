/**
 * PaymentType entity to DTO mapping functions.
 *
 * Provides conversion functions between PaymentType domain entities and
 * their corresponding DTOs for API communication. Handles data transformation
 * and formatting for cross-platform compatibility.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.payment.mapper

import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.features.payment.domain.models.PaymentType

/**
 * Converts PaymentType entity to PaymentTypeResponseDto.
 */
fun PaymentType.toResponse(): PaymentTypeDto.PaymentTypeResponseDto =
    PaymentTypeDto.PaymentTypeResponseDto(
        id = this.id.value,
        name = this.name,
        en = this.enName,
        ch = this.chName,
        isCash = this.isCash,
        isActive = this.isActive,
        isVoucher = this.isVoucher,
        createsReceivable = this.createsReceivable,
        updatedAt = this.updatedAt.toString(),
    )

/**
 * Converts PaymentType entity to PaymentTypeSummaryDto.
 */
fun PaymentType.toSummary(): PaymentTypeDto.PaymentTypeSummaryDto =
    PaymentTypeDto.PaymentTypeSummaryDto(
        id = this.id.value,
        name = this.name,
        isActive = this.isActive,
    )

/**
 * Converts a list of PaymentType entities to PaymentTypeListResponseDto.
 */
fun List<PaymentType>.toListResponse(): PaymentTypeDto.PaymentTypeListResponseDto {
    val responseDtos = this.map { it.toResponse() }
    return PaymentTypeDto.PaymentTypeListResponseDto(
        paymentTypes = responseDtos,
        total = this.size,
        activeCount = this.count { it.isActive },
        inactiveCount = this.count { !it.isActive },
    )
}

/**
 * Converts a list of PaymentType entities to summary DTOs.
 */
fun List<PaymentType>.toSummaryList(): List<PaymentTypeDto.PaymentTypeSummaryDto> = this.map { it.toSummary() }
