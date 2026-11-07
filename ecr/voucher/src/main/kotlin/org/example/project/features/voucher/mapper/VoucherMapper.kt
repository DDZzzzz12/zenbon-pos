/**
 * Voucher entity to DTO mapping functions.
 *
 * Provides conversion methods for Voucher entities to their corresponding DTOs
 * for API responses and data transfer operations. Handles voucher data transformation
 * including value calculations and redemption status.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.voucher.mapper

import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.utils.formatter.formatDecimal

/**
 * Converts Voucher entity to VoucherResponseDto.
 *
 * Handles both system-generated vouchers (with orderItem) and legacy vouchers (without orderItem).
 */
fun Voucher.toResponse(
    tableSessionId: Long? = null,
    currency: String = "EUR",
): VoucherDto.VoucherResponseDto {
    val orderItem = this.orderItem
    val employeeNumber = orderItem.getEmployee().employeeNumber
    return VoucherDto.VoucherResponseDto(
        id = this.id.value,
        sessionId = tableSessionId,
        voucherNumber = this.voucherNumber,
        orderItemId = orderItem.id.value,
        employeeNumber = employeeNumber,
        originalValue = formatDecimal(this.originalValue),
        remainingValue = formatDecimal(this.remainingValue),
        totalRedeemed = formatDecimal(this.totalRedeemed),
        isFullyRedeemed = this.isFullyRedeemed,
        createdAt = this.createdAt.toString(),
        fullyRedeemedAt = this.fullyRedeemedAt?.toString(),
        status = this.status.name,
        invalidationReason = this.invalidationReason,
        invalidatedAt = this.invalidatedAt?.toString(),
        invalidationSource = this.invalidationSource?.name,
        currency = currency,
    )
}

/**
 * Converts VoucherStatistics to VoucherStatisticsDto.
 */
fun VoucherDto.VoucherStatistics.toDto(currency: String = "EUR"): VoucherDto.VoucherStatisticsDto =
    VoucherDto.VoucherStatisticsDto(
        totalVouchers = this.totalVouchers,
        activeVouchers = this.activeVouchers,
        fullyRedeemedVouchers = this.fullyRedeemedVouchers,
        totalValueIssued = this.totalValueIssued,
        totalValueRedeemed = this.totalValueRedeemed,
        totalValueRemaining = this.totalValueRemaining,
        invalidatedVouchers = this.invalidatedVouchers,
        totalValueInvalidated = this.totalValueInvalidated,
        currency = currency,
    )
